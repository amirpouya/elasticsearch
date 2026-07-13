/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.esql.core.type;

import org.elasticsearch.TransportVersion;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.core.Tuple;
import org.elasticsearch.xpack.esql.core.QlIllegalArgumentException;
import org.elasticsearch.xpack.esql.io.stream.PlanStreamInput;
import org.elasticsearch.xpack.esql.io.stream.PlanStreamOutput;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import static org.elasticsearch.xpack.esql.core.type.DataType.KEYWORD;
import static org.elasticsearch.xpack.esql.core.type.DataType.TEXT;

/**
 * Information about a field in an es index with the {@code text} type.
 * <p>
 * Carries the field's mapping analyzer names (index and search), propagated from field-caps, so that consumers such as
 * ES|QL {@code HIGHLIGHT} can re-resolve the analyzer without a live {@code SearchExecutionContext}. Both are
 * {@code null} when the source node predates the analyzer-name propagation, or when the names could not be reconciled
 * across indices (see {@code IndexResolver}).
 */
public class TextEsField extends EsField {

    // Shared with the field-caps analyzer-name propagation (IndexFieldCapabilities): both wire-format additions ship
    // together, so a single transport version gates both.
    private static final TransportVersion MAPPING_ANALYZER_NAMES = TransportVersion.fromName("mapping_analyzer_names");

    @Nullable
    private final String indexAnalyzer;
    @Nullable
    private final String searchAnalyzer;

    public TextEsField(
        String name,
        Map<String, EsField> properties,
        boolean hasDocValues,
        boolean isAlias,
        TimeSeriesFieldType timeSeriesFieldType
    ) {
        this(name, properties, hasDocValues, isAlias, timeSeriesFieldType, null, null);
    }

    public TextEsField(
        String name,
        Map<String, EsField> properties,
        boolean hasDocValues,
        boolean isAlias,
        TimeSeriesFieldType timeSeriesFieldType,
        @Nullable String indexAnalyzer,
        @Nullable String searchAnalyzer
    ) {
        super(name, TEXT, properties, hasDocValues, isAlias, timeSeriesFieldType);
        this.indexAnalyzer = indexAnalyzer;
        this.searchAnalyzer = searchAnalyzer;
    }

    protected TextEsField(StreamInput in) throws IOException {
        this(
            ((PlanStreamInput) in).readCachedString(),
            in.readImmutableMap(EsField::readFrom),
            in.readBoolean(),
            in.readBoolean(),
            readTimeSeriesFieldType(in),
            in.getTransportVersion().supports(MAPPING_ANALYZER_NAMES) ? in.readOptionalString() : null,
            in.getTransportVersion().supports(MAPPING_ANALYZER_NAMES) ? in.readOptionalString() : null
        );
    }

    @Override
    public void writeContent(StreamOutput out) throws IOException {
        ((PlanStreamOutput) out).writeCachedString(getName());
        out.writeMap(getProperties(), (o, x) -> x.writeTo(out));
        out.writeBoolean(isAggregatable());
        out.writeBoolean(isAlias());
        writeTimeSeriesFieldType(out);
        if (out.getTransportVersion().supports(MAPPING_ANALYZER_NAMES)) {
            out.writeOptionalString(indexAnalyzer);
            out.writeOptionalString(searchAnalyzer);
        }
    }

    public String getWriteableName(TransportVersion transportVersion) {
        return "TextEsField";
    }

    /**
     * The name of the field's index-time analyzer, or {@code null} when unknown. Used to re-analyze row text (Query DSL
     * parity). Only built-in/prebuilt names resolve on the coordinator; custom names fall back to the default analyzer.
     */
    @Nullable
    public String indexAnalyzer() {
        return indexAnalyzer;
    }

    /**
     * The name of the field's search-time analyzer, or {@code null} when unknown. Used to tokenize the HIGHLIGHT query.
     * Same resolvability caveat as {@link #indexAnalyzer()}.
     */
    @Nullable
    public String searchAnalyzer() {
        return searchAnalyzer;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (super.equals(o) == false) {
            return false;
        }
        TextEsField that = (TextEsField) o;
        return Objects.equals(indexAnalyzer, that.indexAnalyzer) && Objects.equals(searchAnalyzer, that.searchAnalyzer);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), indexAnalyzer, searchAnalyzer);
    }

    @Override
    public EsField getExactField() {
        Tuple<EsField, String> findExact = findExact();
        if (findExact.v1() == null) {
            throw new QlIllegalArgumentException(findExact.v2());
        }
        return findExact.v1();
    }

    @Override
    public Exact getExactInfo() {
        return PROCESS_EXACT_FIELD.apply(findExact());
    }

    private Tuple<EsField, String> findExact() {
        EsField field = null;
        for (EsField property : getProperties().values()) {
            if (property.getDataType() == KEYWORD && property.getExactInfo().hasExact()) {
                if (field != null) {
                    return new Tuple<>(
                        null,
                        "Multiple exact keyword candidates available for [" + getName() + "]; specify which one to use"
                    );
                }
                field = property;
            }
        }
        if (field == null) {
            return new Tuple<>(
                null,
                "No keyword/multi-field defined exact matches for [" + getName() + "]; define one or use MATCH/QUERY instead"
            );
        }
        return new Tuple<>(field, null);
    }

    private Function<Tuple<EsField, String>, Exact> PROCESS_EXACT_FIELD = tuple -> {
        if (tuple.v1() == null) {
            return new Exact(false, tuple.v2());
        } else {
            return new Exact(true, null);
        }
    };
}
