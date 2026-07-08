/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.planner;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.queryparser.classic.Token;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.elasticsearch.common.lucene.BytesRefs;
import org.elasticsearch.common.lucene.search.Queries;
import org.elasticsearch.common.unit.Fuzziness;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.index.query.AbstractQueryBuilder;
import org.elasticsearch.index.query.MatchPhraseQueryBuilder;
import org.elasticsearch.index.query.MatchQueryBuilder;
import org.elasticsearch.index.query.QueryStringQueryBuilder;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.Expressions;
import org.elasticsearch.xpack.esql.core.expression.FoldContext;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.expression.MapExpression;
import org.elasticsearch.xpack.esql.core.expression.NamedExpression;
import org.elasticsearch.xpack.esql.core.expression.TypeResolutions;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.function.Options;
import org.elasticsearch.xpack.esql.expression.function.fulltext.Kql;
import org.elasticsearch.xpack.esql.expression.function.fulltext.Match;
import org.elasticsearch.xpack.esql.expression.function.fulltext.MatchPhrase;
import org.elasticsearch.xpack.esql.expression.function.fulltext.QueryString;
import org.elasticsearch.xpack.esql.expression.predicate.logical.And;
import org.elasticsearch.xpack.esql.expression.predicate.logical.Not;
import org.elasticsearch.xpack.esql.expression.predicate.logical.Or;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Translates a {@code HIGHLIGHT} query {@link Expression} into one Lucene {@link Query} over the real {@code ON} field names.
 * <p>
 * Verification and execution both call this translator so they accept/reject the same query shapes and options.
 * <p>
 * Translation walks the expression tree directly (not {@code asQuery()}) to stay independent from pre-mapping state.
 */
public final class HighlightQueryTranslator {

    // Option names derived from the canonical query-builder ParseFields, keeping HIGHLIGHT in sync with MATCH/MATCH_PHRASE/QSTR.
    private static final String BOOST_OPTION = AbstractQueryBuilder.BOOST_FIELD.getPreferredName();
    private static final String OPERATOR_OPTION = MatchQueryBuilder.OPERATOR_FIELD.getPreferredName();
    private static final String FUZZINESS_OPTION = Fuzziness.FIELD.getPreferredName();
    private static final String PREFIX_LENGTH_OPTION = MatchQueryBuilder.PREFIX_LENGTH_FIELD.getPreferredName();
    private static final String MAX_EXPANSIONS_OPTION = MatchQueryBuilder.MAX_EXPANSIONS_FIELD.getPreferredName();
    private static final String FUZZY_TRANSPOSITIONS_OPTION = MatchQueryBuilder.FUZZY_TRANSPOSITIONS_FIELD.getPreferredName();
    private static final String MINIMUM_SHOULD_MATCH_OPTION = MatchQueryBuilder.MINIMUM_SHOULD_MATCH_FIELD.getPreferredName();
    private static final String SLOP_OPTION = MatchPhraseQueryBuilder.SLOP_FIELD.getPreferredName();
    private static final String DEFAULT_FIELD_OPTION = QueryStringQueryBuilder.DEFAULT_FIELD_FIELD.getPreferredName();
    private static final String EMPTY_QUERY_REASON = "HIGHLIGHT query is empty";
    private static final String NO_TERMS_REASON = "HIGHLIGHT query produced no terms";
    private static final Set<String> QUERY_STRING_ALLOWED_OPTIONS = Set.of(DEFAULT_FIELD_OPTION);
    private static final FoldContext FOLD_CONTEXT = FoldContext.small();

    // MATCH options with no meaning in coordinator-side highlighting: reject instead of silently ignoring.
    private static final Set<String> MATCH_REJECTED_OPTIONS = Set.of(
        MatchQueryBuilder.FUZZY_REWRITE_FIELD.getPreferredName(),
        MatchQueryBuilder.ZERO_TERMS_QUERY_FIELD.getPreferredName(),
        MatchQueryBuilder.GENERATE_SYNONYMS_PHRASE_QUERY.getPreferredName(),
        MatchQueryBuilder.LENIENT_FIELD.getPreferredName()
    );
    private static final Set<String> MATCH_PHRASE_REJECTED_OPTIONS = Set.of(
        MatchPhraseQueryBuilder.ZERO_TERMS_QUERY_FIELD.getPreferredName()
    );

    private final List<String> fields;
    private final Analyzer defaultAnalyzer;

    private HighlightQueryTranslator(List<String> fields, Analyzer defaultAnalyzer) {
        this.fields = fields;
        this.defaultAnalyzer = defaultAnalyzer;
    }

    /**
     * Translates {@code query} into a single Lucene {@link Query} over the given {@code fields}.
     *
     * @param query            the resolved HIGHLIGHT query expression (a full-text function, a boolean combination of
     *                         them, or a string {@link Literal})
     * @param fields           the real {@code ON} field names, in order
     * @param defaultAnalyzer  the analyzer used to tokenize the query text (always the default Standard analyzer)
     * @throws IllegalArgumentException when the expression, a function, or an option is not supported by HIGHLIGHT
     */
    public static Query translate(Expression query, List<String> fields, Analyzer defaultAnalyzer) {
        return new HighlightQueryTranslator(fields, defaultAnalyzer).doTranslate(query);
    }

    /** Parses a literal query string over the real {@code ON} field names. */
    public static Query translateLiteral(String queryText, List<String> fields, Analyzer analyzer) {
        return parseQueryString(queryStringParser(fields, analyzer), queryText);
    }

    private Query doTranslate(Expression expr) {
        // MatchOperator (':') extends Match, so Match handles both.
        if (expr instanceof Match match) {
            return translateMatch(match);
        }
        if (expr instanceof MatchPhrase matchPhrase) {
            return translateMatchPhrase(matchPhrase);
        }
        if (expr instanceof QueryString queryString) {
            return translateQueryString(queryString);
        }
        if (expr instanceof And and) {
            return translateBoolean(and.left(), and.right(), BooleanClause.Occur.MUST);
        }
        if (expr instanceof Or or) {
            return translateBoolean(or.left(), or.right(), BooleanClause.Occur.SHOULD);
        }
        if (expr instanceof Not not) {
            return new BooleanQuery.Builder().add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST)
                .add(doTranslate(not.field()), BooleanClause.Occur.MUST_NOT)
                .build();
        }
        if (expr instanceof Kql) {
            throw new IllegalArgumentException("HIGHLIGHT does not support [KQL] queries yet");
        }
        if (expr instanceof Literal literal && DataType.isString(literal.dataType())) {
            // String literal behaves like query_string over all ON fields.
            return translateLiteral(BytesRefs.toString(literal.value()), fields, defaultAnalyzer);
        }
        throw new IllegalArgumentException(
            "HIGHLIGHT query must be a full-text function (MATCH, MATCH_PHRASE, QSTR) or a boolean combination of them, found ["
                + expr.sourceText()
                + "]"
        );
    }

    private Query translateBoolean(Expression left, Expression right, BooleanClause.Occur occur) {
        return new BooleanQuery.Builder().add(doTranslate(left), occur).add(doTranslate(right), occur).build();
    }

    private Query translateMatch(Match match) {
        Map<String, Object> options = optionMap(match.options(), match.source(), Match.ALLOWED_OPTIONS);
        rejectOptions(options, MATCH_REJECTED_OPTIONS, "MATCH");
        String field = fieldName(match.field());
        String text = queryText(match.query());
        Query query = createMatchQueryBuilder(options, defaultAnalyzer).createBooleanQuery(field, text, matchOperator(options));
        query = matchNoTermsAsNoDocs(query, "HIGHLIGHT MATCH produced no terms");
        query = Queries.maybeApplyMinimumShouldMatch(query, stringOption(options, MINIMUM_SHOULD_MATCH_OPTION));
        return applyBoost(query, options);
    }

    private Query translateMatchPhrase(MatchPhrase matchPhrase) {
        Map<String, Object> options = optionMap(matchPhrase.options(), matchPhrase.source(), MatchPhrase.ALLOWED_OPTIONS);
        rejectOptions(options, MATCH_PHRASE_REJECTED_OPTIONS, "MATCH_PHRASE");
        String field = fieldName(matchPhrase.field());
        String text = queryText(matchPhrase.query());
        int slop = intOption(options, SLOP_OPTION, 0);

        Query query = new org.apache.lucene.util.QueryBuilder(defaultAnalyzer).createPhraseQuery(field, text, slop);
        query = matchNoTermsAsNoDocs(query, "HIGHLIGHT MATCH_PHRASE produced no terms");
        return applyBoost(query, options);
    }

    private Query translateQueryString(QueryString queryString) {
        Map<String, Object> options = optionMap(queryString.options(), queryString.source(), QueryString.ALLOWED_OPTIONS);
        rejectUnsupportedOptions(options, QUERY_STRING_ALLOWED_OPTIONS, "QSTR");
        String text = queryText(queryString.query());
        String defaultField = stringOption(options, DEFAULT_FIELD_OPTION);
        List<String> targetFields = defaultField != null ? List.of(defaultField) : fields;
        return translateLiteral(text, targetFields, defaultAnalyzer);
    }

    private static Query applyBoost(Query query, Map<String, Object> options) {
        Float boost = floatOption(options, BOOST_OPTION);
        return boost == null ? query : new BoostQuery(query, boost);
    }

    private static void rejectOptions(Map<String, Object> options, Set<String> rejected, String functionName) {
        for (String name : rejected) {
            if (options.containsKey(name)) {
                throw new IllegalArgumentException("HIGHLIGHT does not support the [" + name + "] option of [" + functionName + "]");
            }
        }
    }

    private static void rejectUnsupportedOptions(Map<String, Object> options, Set<String> supported, String functionName) {
        for (String name : options.keySet()) {
            if (supported.contains(name) == false) {
                throw new IllegalArgumentException("HIGHLIGHT does not support the [" + name + "] option of [" + functionName + "]");
            }
        }
    }

    /**
     * Folds the option {@link MapExpression} into a map of already type-converted values, reusing the owning function's
     * {@code ALLOWED_OPTIONS} as the single source of truth for option types (via {@link Options#populateMap}). This
     * yields {@link Float}/{@link Integer}/{@link Boolean}/{@link String} values instead of raw folded expressions, so
     * downstream readers never perform unchecked casts on unconverted literals.
     */
    private static Map<String, Object> optionMap(Expression options, Source source, Map<String, DataType> allowedOptions) {
        if (options instanceof MapExpression mapExpression) {
            Map<String, Object> converted = new HashMap<>();
            Options.populateMap(mapExpression, converted, source, TypeResolutions.ParamOrdinal.SECOND, allowedOptions);
            return converted;
        }
        return Map.of();
    }

    /** Returns the field key used by both translator and operator for a given ON expression. */
    private static String fieldName(Expression field) {
        return field instanceof NamedExpression named ? named.name() : Expressions.name(field);
    }

    private static String queryText(Expression query) {
        return BytesRefs.toString(query.fold(FOLD_CONTEXT));
    }

    @Nullable
    private static String stringOption(Map<String, Object> options, String name) {
        Object value = options.get(name);
        return value == null ? null : value.toString();
    }

    @Nullable
    private static Float floatOption(Map<String, Object> options, String name) {
        return (Float) options.get(name);
    }

    private static int intOption(Map<String, Object> options, String name, int defaultValue) {
        Object value = options.get(name);
        return value == null ? defaultValue : (Integer) value;
    }

    private static boolean boolOption(Map<String, Object> options, String name, boolean defaultValue) {
        Object value = options.get(name);
        return value == null ? defaultValue : (Boolean) value;
    }

    private static Query matchNoTermsAsNoDocs(@Nullable Query query, String reason) {
        return query == null ? new MatchNoDocsQuery(reason) : query;
    }

    private static BooleanClause.Occur matchOperator(Map<String, Object> options) {
        String operator = stringOption(options, OPERATOR_OPTION);
        return "AND".equalsIgnoreCase(operator) ? BooleanClause.Occur.MUST : BooleanClause.Occur.SHOULD;
    }

    private static org.apache.lucene.util.QueryBuilder createMatchQueryBuilder(Map<String, Object> options, Analyzer analyzer) {
        String fuzzinessValue = stringOption(options, FUZZINESS_OPTION);
        if (fuzzinessValue == null) {
            return new org.apache.lucene.util.QueryBuilder(analyzer);
        }
        return new FuzzyQueryBuilder(
            analyzer,
            Fuzziness.fromString(fuzzinessValue),
            intOption(options, PREFIX_LENGTH_OPTION, FuzzyQuery.defaultPrefixLength),
            intOption(options, MAX_EXPANSIONS_OPTION, FuzzyQuery.defaultMaxExpansions),
            boolOption(options, FUZZY_TRANSPOSITIONS_OPTION, FuzzyQuery.defaultTranspositions)
        );
    }

    private static Query parseQueryString(QueryParser parser, String queryText) {
        if (queryText == null || queryText.isBlank()) {
            // An empty query string matches nothing.
            return new MatchNoDocsQuery(EMPTY_QUERY_REASON);
        }
        parser.setAllowLeadingWildcard(true);               // ES query_string default (Lucene defaults to false)
        parser.setDefaultOperator(QueryParser.Operator.OR); // Lucene default; set explicitly for clarity
        try {
            Query query = parser.parse(queryText);
            if (query instanceof BooleanQuery bq && bq.clauses().isEmpty()) {
                // Analyzer removed all terms (for example, "the" with a stop analyzer).
                return new MatchNoDocsQuery(NO_TERMS_REASON);
            }
            return query;
        } catch (ParseException | IllegalArgumentException e) {
            // IllegalArgumentException covers unchecked parser failures (for example, invalid regex/fuzziness).
            throw new IllegalArgumentException("Invalid query [" + queryText + "] in HIGHLIGHT: " + e.getMessage(), e);
        }
    }

    /**
     * Lucene exposes two distinct query-string parsers: {@link QueryParser} targets a single field, while
     * {@link MultiFieldQueryParser} spreads each term across several fields and always wraps the result in a
     * {@link BooleanQuery}. They are not interchangeable for a single field, so pick the one matching the ON-field arity.
     */
    private static QueryParser queryStringParser(List<String> fields, Analyzer analyzer) {
        return switch (fields.size()) {
            case 1 -> new HighlightSingleFieldParser(fields.getFirst(), analyzer);
            default -> new HighlightMultiFieldParser(fields.toArray(String[]::new), analyzer);
        };
    }

    // Match query_string semantics: bare "~" => AUTO, "~N" => validated numeric edit distance.
    private static float queryStringFuzzyDistance(Token fuzzyToken, String termStr) {
        if (fuzzyToken.image.length() == 1) {
            return Fuzziness.AUTO.asDistance(termStr);
        }
        return Fuzziness.fromString(fuzzyToken.image.substring(1)).asDistance(termStr);
    }

    private static final class HighlightSingleFieldParser extends QueryParser {
        HighlightSingleFieldParser(String field, Analyzer analyzer) {
            super(field, analyzer);
        }

        @Override
        protected float getFuzzyDistance(Token fuzzyToken, String termStr) {
            return queryStringFuzzyDistance(fuzzyToken, termStr);
        }
    }

    private static final class HighlightMultiFieldParser extends MultiFieldQueryParser {
        HighlightMultiFieldParser(String[] fields, Analyzer analyzer) {
            super(fields, analyzer);
        }

        @Override
        protected float getFuzzyDistance(Token fuzzyToken, String termStr) {
            return queryStringFuzzyDistance(fuzzyToken, termStr);
        }
    }

    /** QueryBuilder variant that emits Lucene {@link FuzzyQuery} without requiring a search execution context. */
    private static final class FuzzyQueryBuilder extends org.apache.lucene.util.QueryBuilder {
        private final Fuzziness fuzziness;
        private final int prefixLength;
        private final int maxExpansions;
        private final boolean transpositions;

        FuzzyQueryBuilder(Analyzer analyzer, Fuzziness fuzziness, int prefixLength, int maxExpansions, boolean transpositions) {
            super(analyzer);
            this.fuzziness = fuzziness;
            this.prefixLength = prefixLength;
            this.maxExpansions = maxExpansions;
            this.transpositions = transpositions;
        }

        @Override
        protected Query newTermQuery(Term term, float boost) {
            return new FuzzyQuery(term, fuzziness.asDistance(term.text()), prefixLength, maxExpansions, transpositions);
        }
    }
}
