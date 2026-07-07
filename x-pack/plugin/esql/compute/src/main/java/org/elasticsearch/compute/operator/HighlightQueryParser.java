/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.queryparser.classic.Token;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.elasticsearch.common.unit.Fuzziness;

import java.util.List;

/**
 * Parses {@code HIGHLIGHT} query text with Lucene's classic query-string parser.
 * <p>
 * This path runs on the coordinator (no {@code SearchExecutionContext}), so it intentionally uses Lucene parser types.
 * <p>
 * Verification and execution share this class to keep query acceptance rules identical.
 */
public final class HighlightQueryParser {
    private static final String EMPTY_QUERY_REASON = "HIGHLIGHT query is empty";
    private static final String NO_TERMS_REASON = "HIGHLIGHT query produced no terms";

    private HighlightQueryParser() {}

    /** Parses against the synthetic {@link HighlightOperator#CONTENT_FIELD}. */
    public static Query parse(String queryText, Analyzer analyzer) {
        return parse(new HighlightClassicParser(HighlightOperator.CONTENT_FIELD, analyzer), queryText);
    }

    /** Parses against real ON fields; unqualified terms expand across all listed fields. */
    public static Query parse(List<String> fields, String queryText, Analyzer analyzer) {
        return parse(new HighlightMultiFieldParser(fields.toArray(String[]::new), analyzer), queryText);
    }

    private static Query parse(QueryParser parser, String queryText) {
        if (queryText == null || queryText.isBlank()) {
            // Preserve existing empty-query behavior.
            return new MatchNoDocsQuery(EMPTY_QUERY_REASON);
        }
        configureParser(parser);
        try {
            return withEmptyBooleanAsNoDocs(parser.parse(queryText));
        } catch (ParseException | IllegalArgumentException e) {
            // IllegalArgumentException covers unchecked parser failures (for example, invalid regex/fuzziness).
            throw new IllegalArgumentException("Invalid query [" + queryText + "] in HIGHLIGHT: " + e.getMessage(), e);
        }
    }

    private static void configureParser(QueryParser parser) {
        parser.setAllowLeadingWildcard(true);               // ES query_string default (Lucene defaults to false)
        parser.setDefaultOperator(QueryParser.Operator.OR); // Lucene default; set explicitly for clarity
    }

    private static Query withEmptyBooleanAsNoDocs(Query query) {
        if (query instanceof BooleanQuery bq && bq.clauses().isEmpty()) {
            // Analyzer removed all terms (for example, "the" with a stop analyzer).
            return new MatchNoDocsQuery(NO_TERMS_REASON);
        }
        return query;
    }

    // Match query_string semantics: bare "~" => AUTO, "~N" => validated numeric edit distance.
    private static float fuzzyDistance(Token fuzzyToken, String termStr) {
        if (fuzzyToken.image.length() == 1) {
            return Fuzziness.AUTO.asDistance(termStr);
        }
        return Fuzziness.fromString(fuzzyToken.image.substring(1)).asDistance(termStr);
    }

    private static final class HighlightClassicParser extends QueryParser {
        HighlightClassicParser(String field, Analyzer analyzer) {
            super(field, analyzer);
        }

        @Override
        protected float getFuzzyDistance(Token fuzzyToken, String termStr) {
            return fuzzyDistance(fuzzyToken, termStr);
        }
    }

    private static final class HighlightMultiFieldParser extends MultiFieldQueryParser {
        HighlightMultiFieldParser(String[] fields, Analyzer analyzer) {
            super(fields, analyzer);
        }

        @Override
        protected float getFuzzyDistance(Token fuzzyToken, String termStr) {
            return fuzzyDistance(fuzzyToken, termStr);
        }
    }
}
