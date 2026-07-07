/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.planner;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.MapExpression;
import org.elasticsearch.xpack.esql.expression.function.fulltext.Kql;
import org.elasticsearch.xpack.esql.expression.function.fulltext.Match;
import org.elasticsearch.xpack.esql.expression.function.fulltext.MatchPhrase;
import org.elasticsearch.xpack.esql.expression.function.fulltext.QueryString;
import org.elasticsearch.xpack.esql.expression.predicate.logical.And;
import org.elasticsearch.xpack.esql.expression.predicate.logical.Not;
import org.elasticsearch.xpack.esql.expression.predicate.logical.Or;

import java.util.ArrayList;
import java.util.List;

import static org.elasticsearch.xpack.esql.EsqlTestUtils.TEST_CFG;
import static org.elasticsearch.xpack.esql.EsqlTestUtils.getFieldAttribute;
import static org.elasticsearch.xpack.esql.EsqlTestUtils.of;
import static org.elasticsearch.xpack.esql.core.tree.Source.EMPTY;
import static org.elasticsearch.xpack.esql.core.type.DataType.KEYWORD;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;

/**
 * Unit tests for {@link HighlightQueryTranslator}, asserting the shape of the Lucene {@link Query} produced for each
 * supported HIGHLIGHT query expression. These pin the query-construction contract that the end-to-end highlighting in
 * {@code HighlightOperatorTests} and the csv-spec fixtures rely on. HIGHLIGHT always tokenizes with the default
 * {@link StandardAnalyzer}.
 */
public class HighlightQueryTranslatorTests extends ESTestCase {

    private static final List<String> TITLE = List.of("title");
    private static final List<String> TITLE_BODY = List.of("title", "body");

    private static Query translate(Expression query, List<String> fields) {
        return HighlightQueryTranslator.translate(query, fields, new StandardAnalyzer());
    }

    private static Match match(String field, String text, MapExpression options) {
        return new Match(EMPTY, getFieldAttribute(field, KEYWORD), of(text), options, TEST_CFG);
    }

    private static MatchPhrase matchPhrase(String field, String text, MapExpression options) {
        return new MatchPhrase(EMPTY, getFieldAttribute(field, KEYWORD), of(text), options);
    }

    private static QueryString queryString(String text, MapExpression options) {
        return new QueryString(EMPTY, of(text), options, TEST_CFG);
    }

    private static MapExpression options(Object... keyValues) {
        List<Expression> entries = new ArrayList<>(keyValues.length);
        for (Object keyValue : keyValues) {
            entries.add(of(keyValue));
        }
        return new MapExpression(EMPTY, entries);
    }

    public void testMatchSingleTerm() {
        Query query = translate(match("title", "fox", null), TITLE);
        TermQuery term = asInstanceOf(TermQuery.class, query);
        assertThat(term.getTerm(), equalTo(new Term("title", "fox")));
    }

    public void testMatchMultipleTermsDefaultsToShould() {
        BooleanQuery bq = asInstanceOf(BooleanQuery.class, translate(match("title", "quick fox", null), TITLE));
        assertThat(bq.clauses(), hasSize(2));
        for (BooleanClause clause : bq.clauses()) {
            assertThat(clause.occur(), equalTo(BooleanClause.Occur.SHOULD));
        }
        assertThat(terms(bq), containsInAnyOrder(new Term("title", "quick"), new Term("title", "fox")));
    }

    public void testMatchOperatorAnd() {
        BooleanQuery bq = asInstanceOf(BooleanQuery.class, translate(match("title", "quick fox", options("operator", "AND")), TITLE));
        assertThat(bq.clauses(), hasSize(2));
        for (BooleanClause clause : bq.clauses()) {
            assertThat(clause.occur(), equalTo(BooleanClause.Occur.MUST));
        }
    }

    public void testMatchMinimumShouldMatch() {
        BooleanQuery bq = asInstanceOf(
            BooleanQuery.class,
            translate(match("title", "quick brown fox", options("minimum_should_match", "2")), TITLE)
        );
        assertThat(bq.clauses(), hasSize(3));
        assertThat(bq.getMinimumNumberShouldMatch(), equalTo(2));
    }

    public void testMatchFuzziness() {
        // AUTO fuzziness gives a term of length 3-5 a single edit.
        FuzzyQuery fuzzy = asInstanceOf(FuzzyQuery.class, translate(match("title", "fox", options("fuzziness", "AUTO")), TITLE));
        assertThat(fuzzy.getTerm(), equalTo(new Term("title", "fox")));
        assertThat(fuzzy.getMaxEdits(), equalTo(1));
    }

    public void testMatchFuzzinessExplicitDistance() {
        FuzzyQuery fuzzy = asInstanceOf(FuzzyQuery.class, translate(match("title", "fox", options("fuzziness", "2")), TITLE));
        assertThat(fuzzy.getMaxEdits(), equalTo(2));
    }

    public void testMatchBoost() {
        BoostQuery boost = asInstanceOf(BoostQuery.class, translate(match("title", "fox", options("boost", 2.0)), TITLE));
        assertThat(boost.getBoost(), equalTo(2.0f));
        assertThat(boost.getQuery(), instanceOf(TermQuery.class));
    }

    public void testMatchRejectsUnsupportedOption() {
        IllegalArgumentException e = expectThrows(
            IllegalArgumentException.class,
            () -> translate(match("title", "fox", options("lenient", true)), TITLE)
        );
        assertThat(e.getMessage(), equalTo("HIGHLIGHT does not support the [lenient] option of [MATCH]"));
    }

    public void testMatchPhrase() {
        PhraseQuery phrase = asInstanceOf(PhraseQuery.class, translate(matchPhrase("title", "quick fox", null), TITLE));
        assertThat(phrase.getTerms(), equalTo(new Term[] { new Term("title", "quick"), new Term("title", "fox") }));
        assertThat(phrase.getSlop(), equalTo(0));
    }

    public void testMatchPhraseSlop() {
        PhraseQuery phrase = asInstanceOf(PhraseQuery.class, translate(matchPhrase("title", "quick fox", options("slop", 2)), TITLE));
        assertThat(phrase.getSlop(), equalTo(2));
    }

    public void testMatchPhraseRejectsUnsupportedOption() {
        IllegalArgumentException e = expectThrows(
            IllegalArgumentException.class,
            () -> translate(matchPhrase("title", "fox", options("zero_terms_query", "all")), TITLE)
        );
        assertThat(e.getMessage(), equalTo("HIGHLIGHT does not support the [zero_terms_query] option of [MATCH_PHRASE]"));
    }

    public void testQueryStringFieldQualifiedTargetsThatField() {
        // A field-qualified term hits only that field, even though both are ON fields (require_field_match parity).
        Query query = translate(queryString("title:fox", null), TITLE_BODY);
        assertThat(terms(query), containsInAnyOrder(new Term("title", "fox")));
    }

    public void testQueryStringUnqualifiedTermExpandsOverAllFields() {
        BooleanQuery bq = asInstanceOf(BooleanQuery.class, translate(queryString("fox", null), TITLE_BODY));
        assertThat(bq.clauses(), hasSize(2));
        for (BooleanClause clause : bq.clauses()) {
            assertThat(clause.occur(), equalTo(BooleanClause.Occur.SHOULD));
        }
        assertThat(terms(bq), containsInAnyOrder(new Term("title", "fox"), new Term("body", "fox")));
    }

    public void testQueryStringDefaultFieldOption() {
        Query query = translate(queryString("fox", options("default_field", "body")), TITLE_BODY);
        assertThat(terms(query), containsInAnyOrder(new Term("body", "fox")));
    }

    public void testQueryStringRejectsUnsupportedOption() {
        IllegalArgumentException e = expectThrows(
            IllegalArgumentException.class,
            () -> translate(queryString("fox", options("fuzziness", "AUTO")), TITLE_BODY)
        );
        assertThat(e.getMessage(), equalTo("HIGHLIGHT does not support the [fuzziness] option of [QSTR]"));
    }

    public void testAnd() {
        And and = new And(EMPTY, match("title", "fox", null), match("body", "bar", null));
        BooleanQuery bq = asInstanceOf(BooleanQuery.class, translate(and, TITLE_BODY));
        assertThat(bq.clauses(), hasSize(2));
        for (BooleanClause clause : bq.clauses()) {
            assertThat(clause.occur(), equalTo(BooleanClause.Occur.MUST));
        }
        assertThat(terms(bq), containsInAnyOrder(new Term("title", "fox"), new Term("body", "bar")));
    }

    public void testOr() {
        Or or = new Or(EMPTY, match("title", "fox", null), match("body", "bar", null));
        BooleanQuery bq = asInstanceOf(BooleanQuery.class, translate(or, TITLE_BODY));
        assertThat(bq.clauses(), hasSize(2));
        for (BooleanClause clause : bq.clauses()) {
            assertThat(clause.occur(), equalTo(BooleanClause.Occur.SHOULD));
        }
    }

    public void testNot() {
        Not not = new Not(EMPTY, match("title", "fox", null));
        BooleanQuery bq = asInstanceOf(BooleanQuery.class, translate(not, TITLE));
        assertThat(bq.clauses(), hasSize(2));
        BooleanClause must = bq.clauses().stream().filter(c -> c.occur() == BooleanClause.Occur.MUST).findFirst().orElseThrow();
        BooleanClause mustNot = bq.clauses().stream().filter(c -> c.occur() == BooleanClause.Occur.MUST_NOT).findFirst().orElseThrow();
        assertThat(must.query(), instanceOf(MatchAllDocsQuery.class));
        assertThat(mustNot.query(), instanceOf(TermQuery.class));
    }

    public void testNestedBoolean() {
        // MATCH(title,"fox") AND (MATCH(title,"quick") OR MATCH(body,"bar"))
        And and = new And(EMPTY, match("title", "fox", null), new Or(EMPTY, match("title", "quick", null), match("body", "bar", null)));
        BooleanQuery bq = asInstanceOf(BooleanQuery.class, translate(and, TITLE_BODY));
        assertThat(bq.clauses(), hasSize(2));
        assertThat(terms(bq), containsInAnyOrder(new Term("title", "fox"), new Term("title", "quick"), new Term("body", "bar")));
    }

    public void testKqlIsRejected() {
        IllegalArgumentException e = expectThrows(
            IllegalArgumentException.class,
            () -> translate(new Kql(EMPTY, of("fox"), null, TEST_CFG), TITLE)
        );
        assertThat(e.getMessage(), equalTo("HIGHLIGHT does not support [KQL] queries yet"));
    }

    public void testLiteralParsesOverAllFields() {
        BooleanQuery bq = asInstanceOf(BooleanQuery.class, translate(of("fox"), TITLE_BODY));
        assertThat(terms(bq), containsInAnyOrder(new Term("title", "fox"), new Term("body", "fox")));
    }

    public void testUnsupportedExpressionShapeIsRejected() {
        IllegalArgumentException e = expectThrows(
            IllegalArgumentException.class,
            () -> translate(getFieldAttribute("title", KEYWORD), TITLE)
        );
        assertThat(e.getMessage(), containsString("HIGHLIGHT query must be a full-text function"));
    }

    /**
     * Recursively collects every {@link Term} referenced by the query, unwrapping the boolean/boost/phrase/term/fuzzy
     * shapes the translator can emit, so tests can assert the target field of each term regardless of nesting.
     */
    private static List<Term> terms(Query query) {
        List<Term> collected = new ArrayList<>();
        collectTerms(query, collected);
        return collected;
    }

    private static void collectTerms(Query query, List<Term> collected) {
        if (query instanceof TermQuery term) {
            collected.add(term.getTerm());
        } else if (query instanceof FuzzyQuery fuzzy) {
            collected.add(fuzzy.getTerm());
        } else if (query instanceof PhraseQuery phrase) {
            collected.addAll(List.of(phrase.getTerms()));
        } else if (query instanceof BoostQuery boost) {
            collectTerms(boost.getQuery(), collected);
        } else if (query instanceof BooleanQuery bool) {
            bool.clauses().forEach(clause -> collectTerms(clause.query(), collected));
        } else {
            throw new AssertionError("unexpected query type: " + query.getClass());
        }
    }
}
