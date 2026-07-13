/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.action.fieldcaps;

import org.elasticsearch.TransportVersion;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.util.StringLiteralDeduplicator;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.index.mapper.TimeSeriesParams;

import java.io.IOException;
import java.util.Map;

/**
 * Describes the capabilities of a field in a single index.
 *
 * @param name           The name of the field.
 * @param type           The type associated with the field.
 * @param isSearchable   Whether this field is indexed for search.
 * @param isAggregatable Whether this field can be aggregated on.
 * @param isInference    Whether this field is an inference field.
 * @param meta           Metadata about the field.
 * @param indexAnalyzer  The name of the field's index-time analyzer, or {@code null} for fields that have no
 *                       text-search information (e.g. numeric fields) or no index analyzer (e.g. runtime fields).
 *                       Only built-in/prebuilt names are resolvable on the coordinator; index-custom names are still
 *                       carried but will not resolve there.
 * @param searchAnalyzer The name of the field's search-time analyzer, or {@code null} when the field has no
 *                       text-search information. Same resolvability caveat as {@code indexAnalyzer}.
 */

public record IndexFieldCapabilities(
    String name,
    String type,
    boolean isMetadatafield,
    boolean isSearchable,
    boolean isAggregatable,
    boolean isInference,
    boolean isDimension,
    TimeSeriesParams.MetricType metricType,
    Map<String, String> meta,
    @Nullable String indexAnalyzer,
    @Nullable String searchAnalyzer
) implements Writeable {

    // Gates the field-caps and ES|QL EsField analyzer-name propagation, both introduced together, so a single shared
    // transport version guards both wire-format additions (see also TextEsField).
    static final TransportVersion MAPPING_ANALYZER_NAMES = TransportVersion.fromName("mapping_analyzer_names");

    private static final StringLiteralDeduplicator typeStringDeduplicator = new StringLiteralDeduplicator();

    public static IndexFieldCapabilities readFrom(StreamInput in) throws IOException {
        String name = in.readString();
        String type = typeStringDeduplicator.deduplicate(in.readString());
        boolean isMetadatafield = in.readBoolean();
        boolean isSearchable = in.readBoolean();
        boolean isAggregatable = in.readBoolean();
        boolean isDimension = in.readBoolean();
        TimeSeriesParams.MetricType metricType = in.readOptionalEnum(TimeSeriesParams.MetricType.class);
        Map<String, String> meta = in.readImmutableMap(StreamInput::readString);
        boolean isInference = in.getTransportVersion().supports(FieldCapabilities.FIELD_CAPS_INFERENCE_FIELD) && in.readBoolean();
        String indexAnalyzer = null;
        String searchAnalyzer = null;
        if (in.getTransportVersion().supports(MAPPING_ANALYZER_NAMES)) {
            indexAnalyzer = in.readOptionalString();
            searchAnalyzer = in.readOptionalString();
        }
        return new IndexFieldCapabilities(
            name,
            type,
            isMetadatafield,
            isSearchable,
            isAggregatable,
            isInference,
            isDimension,
            metricType,
            meta,
            indexAnalyzer,
            searchAnalyzer
        );
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(name);
        out.writeString(type);
        out.writeBoolean(isMetadatafield);
        out.writeBoolean(isSearchable);
        out.writeBoolean(isAggregatable);
        out.writeBoolean(isDimension);
        out.writeOptionalEnum(metricType);
        out.writeMap(meta, StreamOutput::writeString);
        if (out.getTransportVersion().supports(FieldCapabilities.FIELD_CAPS_INFERENCE_FIELD)) {
            out.writeBoolean(isInference);
        }
        if (out.getTransportVersion().supports(MAPPING_ANALYZER_NAMES)) {
            out.writeOptionalString(indexAnalyzer);
            out.writeOptionalString(searchAnalyzer);
        }
    }

}
