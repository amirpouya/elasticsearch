/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.action.fieldcaps;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.FieldInfos;
import org.elasticsearch.common.Strings;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.AnalyzerScope;
import org.elasticsearch.index.analysis.IndexAnalyzers;
import org.elasticsearch.index.analysis.NamedAnalyzer;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.MapperServiceTestCase;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.plugins.FieldPredicate;

import java.io.IOException;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link FieldCapabilitiesFetcher#retrieveFieldCaps} propagates the configured index/search analyzer
 * names into {@link IndexFieldCapabilities}. Only the analyzer <em>name</em> is propagated (never a resolved instance),
 * so these tests register {@link StandardAnalyzer} under several names rather than depending on analysis-common's
 * language analyzers, which are not on the server unit-test classpath.
 */
public class FieldCapabilitiesFetcherAnalyzerTests extends MapperServiceTestCase {

    @Override
    protected IndexAnalyzers createIndexAnalyzers(IndexSettings indexSettings) {
        return IndexAnalyzers.of(
            Map.of(
                "default",
                new NamedAnalyzer("default", AnalyzerScope.INDEX, new StandardAnalyzer()),
                "custom_index",
                new NamedAnalyzer("custom_index", AnalyzerScope.INDEX, new StandardAnalyzer()),
                "custom_search",
                new NamedAnalyzer("custom_search", AnalyzerScope.INDEX, new StandardAnalyzer())
            )
        );
    }

    public void testTextFieldPropagatesAnalyzerNames() throws IOException {
        MapperService mapperService = createMapperService("""
            { "_doc" : {
              "properties" : {
                "body" : { "type" : "text", "analyzer" : "custom_index", "search_analyzer" : "custom_search" },
                "plain" : { "type" : "text" },
                "count" : { "type" : "long" }
              }
            } }
            """);
        SearchExecutionContext sec = createSearchExecutionContext(mapperService);

        Map<String, IndexFieldCapabilities> response = FieldCapabilitiesFetcher.retrieveFieldCaps(
            sec,
            s -> true,
            Strings.EMPTY_ARRAY,
            Strings.EMPTY_ARRAY,
            FieldPredicate.ACCEPT_ALL,
            getMockIndexShard(),
            true
        );

        IndexFieldCapabilities body = response.get("body");
        assertNotNull(body);
        assertEquals("custom_index", body.indexAnalyzer());
        assertEquals("custom_search", body.searchAnalyzer());

        // A plain text field carries the default analyzer under both slots.
        IndexFieldCapabilities plain = response.get("plain");
        assertNotNull(plain);
        assertEquals("default", plain.indexAnalyzer());
        assertEquals("default", plain.searchAnalyzer());

        // Numeric fields are not tokenized, so no analyzer names are propagated.
        IndexFieldCapabilities count = response.get("count");
        assertNotNull(count);
        assertNull(count.indexAnalyzer());
        assertNull(count.searchAnalyzer());
    }

    public void testKeywordFieldDoesNotPropagateAnalyzerNames() throws IOException {
        // Keyword fields are not tokenized; highlight resolves them to the keyword analyzer client-side (at the
        // planner), so there is no reason to propagate the internal keyword analyzer name over the wire.
        MapperService mapperService = createMapperService("""
            { "_doc" : {
              "properties" : {
                "tag" : { "type" : "keyword" }
              }
            } }
            """);
        SearchExecutionContext sec = createSearchExecutionContext(mapperService);

        Map<String, IndexFieldCapabilities> response = FieldCapabilitiesFetcher.retrieveFieldCaps(
            sec,
            s -> true,
            Strings.EMPTY_ARRAY,
            Strings.EMPTY_ARRAY,
            FieldPredicate.ACCEPT_ALL,
            getMockIndexShard(),
            true
        );

        IndexFieldCapabilities tag = response.get("tag");
        assertNotNull(tag);
        assertNull(tag.indexAnalyzer());
        assertNull(tag.searchAnalyzer());
    }

    public void testRuntimeFieldHasNoIndexAnalyzerAndDoesNotThrow() throws IOException {
        // Runtime fields are not present in the mapping's index analyzers; the f -> null fallback must keep the
        // index-analyzer lookup from throwing (regression guard for the runtime-field path).
        MapperService mapperService = createMapperService("""
            { "_doc" : {
              "runtime" : {
                "body" : { "type" : "keyword" }
              }
            } }
            """);
        SearchExecutionContext sec = createSearchExecutionContext(mapperService);

        Map<String, IndexFieldCapabilities> response = FieldCapabilitiesFetcher.retrieveFieldCaps(
            sec,
            s -> true,
            Strings.EMPTY_ARRAY,
            Strings.EMPTY_ARRAY,
            FieldPredicate.ACCEPT_ALL,
            getMockIndexShard(),
            true
        );

        IndexFieldCapabilities body = response.get("body");
        assertNotNull(body);
        assertNull(body.indexAnalyzer());
    }

    private IndexShard getMockIndexShard() {
        IndexShard indexShard = mock(IndexShard.class);
        when(indexShard.getFieldInfos()).thenReturn(FieldInfos.EMPTY);
        return indexShard;
    }
}
