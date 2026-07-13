/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.esql.session;

import org.elasticsearch.TransportVersion;
import org.elasticsearch.action.fieldcaps.FieldCapabilitiesIndexResponse;
import org.elasticsearch.action.fieldcaps.FieldCapabilitiesResponse;
import org.elasticsearch.action.fieldcaps.IndexFieldCapabilities;
import org.elasticsearch.action.fieldcaps.IndexFieldCapabilitiesBuilder;
import org.elasticsearch.index.IndexMode;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.core.type.EsField;
import org.elasticsearch.xpack.esql.core.type.TextEsField;
import org.elasticsearch.xpack.esql.index.IndexResolution;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.nullValue;

/**
 * Exercises {@link IndexResolver#mergedMappings} for the analyzer-name reduction added for ES|QL HIGHLIGHT. A text
 * field's index/search analyzer names are propagated onto {@link TextEsField} only when every mapped index agrees on a
 * non-null name; any disagreement, or a missing name in any mapped index, collapses to {@code null} so that HIGHLIGHT
 * falls back to the default analyzer. Unmapped indices contribute no field-caps entry and therefore do not affect the
 * merge.
 */
public class IndexResolverAnalyzerMergeTests extends ESTestCase {

    private static final String FIELD = "message";

    public void testSingleIndexPropagatesBothNames() {
        TextEsField field = resolveText(textCaps("english", "standard"));
        assertThat(field.indexAnalyzer(), equalTo("english"));
        assertThat(field.searchAnalyzer(), equalTo("standard"));
    }

    public void testTwoIndicesAgreeingPropagate() {
        TextEsField field = resolveText(textCaps("english", "standard"), textCaps("english", "standard"));
        assertThat(field.indexAnalyzer(), equalTo("english"));
        assertThat(field.searchAnalyzer(), equalTo("standard"));
    }

    public void testDisagreementDropsToNull() {
        // The index analyzers disagree (english vs french) so that name drops to null, while the search analyzers
        // agree and survive the merge - the two slots are reduced independently.
        TextEsField field = resolveText(textCaps("english", "standard"), textCaps("french", "standard"));
        assertThat(field.indexAnalyzer(), nullValue());
        assertThat(field.searchAnalyzer(), equalTo("standard"));
    }

    public void testMissingNameInOneIndexDropsToNull() {
        TextEsField field = resolveText(textCaps("english", "standard"), textCaps(null, "standard"));
        assertThat(field.indexAnalyzer(), nullValue());
        assertThat(field.searchAnalyzer(), equalTo("standard"));
    }

    public void testPartiallyMappedKeepsMappedIndexName() {
        // Two indices, but only one maps the field (the other contributes no field-caps entry). The merge sees a single
        // response and keeps its names, exactly as the single-index case.
        FieldCapabilitiesIndexResponse mapped = indexResponse("mapped", Map.of(FIELD, textCaps("english", "standard")));
        FieldCapabilitiesIndexResponse unmapped = indexResponse("unmapped", Map.of());
        TextEsField field = resolveText(mapped, unmapped);
        assertThat(field.indexAnalyzer(), equalTo("english"));
        assertThat(field.searchAnalyzer(), equalTo("standard"));
    }

    private static IndexFieldCapabilitiesBuilder textCaps(String indexAnalyzer, String searchAnalyzer) {
        return new IndexFieldCapabilitiesBuilder(FIELD, "text").indexAnalyzer(indexAnalyzer).searchAnalyzer(searchAnalyzer);
    }

    private static FieldCapabilitiesIndexResponse indexResponse(String index, Map<String, IndexFieldCapabilitiesBuilder> fields) {
        Map<String, IndexFieldCapabilities> built = new HashMap<>();
        fields.forEach((name, builder) -> built.put(name, builder.build()));
        return new FieldCapabilitiesIndexResponse(index, index, built, true, IndexMode.STANDARD);
    }

    private TextEsField resolveText(IndexFieldCapabilitiesBuilder... perIndexCaps) {
        List<FieldCapabilitiesIndexResponse> responses = new ArrayList<>();
        int i = 0;
        for (IndexFieldCapabilitiesBuilder caps : perIndexCaps) {
            String index = "idx-" + (i++);
            responses.add(indexResponse(index, Map.of(FIELD, caps)));
        }
        return resolveText(responses.toArray(new FieldCapabilitiesIndexResponse[0]));
    }

    private TextEsField resolveText(FieldCapabilitiesIndexResponse... responses) {
        FieldCapabilitiesResponse caps = FieldCapabilitiesResponse.builder().withIndexResponses(List.of(responses)).build();
        IndexResolution resolution = IndexResolver.mergedMappings(
            "idx-*",
            false,
            new IndexResolver.FieldsInfo(caps, TransportVersion.current(), false, false, false, false, true),
            false,
            IndexResolver.DO_NOT_GROUP
        );
        EsField field = resolution.get().mapping().get(FIELD);
        assertThat(field, instanceOf(TextEsField.class));
        return (TextEsField) field;
    }
}
