/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plugin;

import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.analysis.common.CommonAnalysisPlugin;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.xpack.esql.action.AbstractEsqlIntegTestCase;
import org.junit.Before;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;

/**
 * End-to-end coverage for per-field analyzer resolution in {@code HIGHLIGHT}. Each {@code ON} field is highlighted with
 * its own mapping analyzer (resolved on the coordinator from the names propagated via field-caps): the index analyzer
 * re-analyzes the row text and the search analyzer tokenizes the query. This exercises the whole chain that the unit
 * tests cover in isolation - field-caps propagation, {@code TextEsField} plumbing, the planner resolution, the query
 * translator and the highlight operator.
 * <p>
 * Runs in-process (no ML native code / full distribution), so unlike the {@code 182_highlight_analyzer} YAML suite it
 * can be executed on a developer workstation. It registers {@link CommonAnalysisPlugin} to make the {@code english}
 * language analyzer available.
 */
public class HighlightAnalyzerIT extends AbstractEsqlIntegTestCase {

    private static final String ENGLISH_INDEX = "hl_english";
    private static final String STANDARD_INDEX = "hl_standard";

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        List<Class<? extends Plugin>> plugins = new ArrayList<>(super.nodePlugins());
        plugins.add(CommonAnalysisPlugin.class);
        return plugins;
    }

    @Before
    public void setupIndices() {
        var client = client().admin().indices();
        assertAcked(
            client.prepareCreate(ENGLISH_INDEX)
                .setSettings(Settings.builder().put("index.number_of_shards", 1))
                .setMapping("id", "type=integer", "content", "type=text,analyzer=english", "body", "type=text", "tag", "type=keyword")
        );
        // Same "content" field name, but analyzed with the default (standard) analyzer - used to prove that a
        // cross-index analyzer disagreement collapses to the default analyzer.
        assertAcked(
            client.prepareCreate(STANDARD_INDEX)
                .setSettings(Settings.builder().put("index.number_of_shards", 1))
                .setMapping("id", "type=integer", "content", "type=text")
        );
        client().prepareBulk()
            .add(
                new IndexRequest(ENGLISH_INDEX).id("1")
                    .source("id", 1, "content", "He was running fast", "body", "the quick brown fox", "tag", "high priority")
            )
            .add(new IndexRequest(STANDARD_INDEX).id("2").source("id", 2, "content", "He was running fast"))
            .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
            .get();
        ensureYellow(ENGLISH_INDEX, STANDARD_INDEX);
    }

    public void testEnglishAnalyzerStemsRowTextAndQuery() {
        var query = """
            FROM hl_english
            | WHERE id == 1
            | HIGHLIGHT "run" ON content
            | KEEP highlight_content
            """;
        try (var resp = run(query)) {
            assertColumnNames(resp.columns(), List.of("highlight_content"));
            assertColumnTypes(resp.columns(), List.of("keyword"));
            assertValues(resp.values(), List.of(List.of("He was <em>running</em> fast")));
        }
    }

    public void testDefaultAnalyzerHighlightsExactTerm() {
        var query = """
            FROM hl_english
            | WHERE id == 1
            | HIGHLIGHT "fox" ON body
            | KEEP highlight_body
            """;
        try (var resp = run(query)) {
            assertColumnNames(resp.columns(), List.of("highlight_body"));
            assertValues(resp.values(), List.of(List.of("the quick brown <em>fox</em>")));
        }
    }

    public void testDefaultAnalyzerDoesNotStem() {
        // The standard analyzer keeps "running" verbatim, so the stem "run" never matches and the field is null.
        var query = """
            FROM hl_standard
            | WHERE id == 2
            | HIGHLIGHT "run" ON content
            | KEEP highlight_content
            """;
        try (var resp = run(query)) {
            assertValues(resp.values(), List.of(Collections.singletonList(null)));
        }
    }

    public void testKeywordFieldHighlightsWholeValue() {
        var query = """
            FROM hl_english
            | WHERE id == 1
            | HIGHLIGHT "high priority" ON tag
            | KEEP highlight_tag
            """;
        try (var resp = run(query)) {
            assertColumnNames(resp.columns(), List.of("highlight_tag"));
            assertValues(resp.values(), List.of(List.of("<em>high priority</em>")));
        }
    }

    public void testKeywordFieldDoesNotHighlightPartialTerm() {
        // Keyword fields index the whole value as one token, so a partial term never matches -> null.
        var query = """
            FROM hl_english
            | WHERE id == 1
            | HIGHLIGHT "high" ON tag
            | KEEP highlight_tag
            """;
        try (var resp = run(query)) {
            assertValues(resp.values(), List.of(Collections.singletonList(null)));
        }
    }

    public void testConflictingPerIndexAnalyzersFallBackToDefault() {
        // content is english in one index and standard in the other; the names disagree, so the merged analyzer drops
        // to the default. The english document is therefore highlighted with the standard analyzer, which does not
        // stem, so "run" no longer matches "running" -> null.
        var query = """
            FROM hl_english,hl_standard
            | WHERE id == 1
            | HIGHLIGHT "run" ON content
            | KEEP highlight_content
            """;
        try (var resp = run(query)) {
            assertValues(resp.values(), List.of(Collections.singletonList(null)));
        }
    }
}
