/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator;

import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.RegexpQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.WildcardQuery;
import org.elasticsearch.test.ESTestCase;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.startsWith;

/**
 * Unit tests for {@link HighlightQueryParser}, asserting the shape of the parsed {@link Query} for each supported
 * {@code query_string} construct. The end-to-end highlighting behavior is covered by {@code HighlightOperatorTests}.
 */
public class HighlightQueryParserTests extends ESTestCase {

    private static Query parse(String text) {
        return HighlightQueryParser.parse(text, new StandardAnalyzer());
    }

    public void testDisjunctionOfTerms() {
        BooleanQuery bq = asInstanceOf(BooleanQuery.class, parse("foo bar"));
        assertThat(bq.clauses(), hasSize(2));
        for (BooleanClause clause : bq.clauses()) {
            assertThat(clause.occur(), equalTo(BooleanClause.Occur.SHOULD));
            assertThat(clause.query(), instanceOf(TermQuery.class));
        }
    }

    public void testBlankInputIsMatchNoDocs() {
        assertThat(parse(""), instanceOf(MatchNoDocsQuery.class));
        assertThat(parse("   "), instanceOf(MatchNoDocsQuery.class));
        assertThat(HighlightQueryParser.parse(null, new StandardAnalyzer()), instanceOf(MatchNoDocsQuery.class));
    }

    public void testAllTermsFilteredIsMatchNoDocs() {
        // "the" is a stop word, so the parsed query has no clauses and is normalized to MatchNoDocsQuery.
        Query query = HighlightQueryParser.parse("the", new StandardAnalyzer(EnglishAnalyzer.ENGLISH_STOP_WORDS_SET));
        assertThat(query, instanceOf(MatchNoDocsQuery.class));
    }

    public void testQuotedPhrase() {
        assertThat(parse("\"a b\""), instanceOf(PhraseQuery.class));
    }

    public void testPrefix() {
        assertThat(parse("fo*"), instanceOf(PrefixQuery.class));
    }

    public void testWildcard() {
        assertThat(parse("f?x"), instanceOf(WildcardQuery.class));
    }

    public void testLeadingWildcardAllowed() {
        assertThat(parse("*ox"), instanceOf(WildcardQuery.class));
    }

    public void testBareFuzzyUsesAutoDistance() {
        // AUTO fuzziness gives a term of length 3-5 a single edit.
        FuzzyQuery query = asInstanceOf(FuzzyQuery.class, parse("quick~"));
        assertThat(query.getMaxEdits(), equalTo(1));
    }

    public void testShortBareFuzzyIsZeroEdits() {
        // AUTO fuzziness gives a term of length <= 2 zero edits.
        FuzzyQuery query = asInstanceOf(FuzzyQuery.class, parse("fx~"));
        assertThat(query.getMaxEdits(), equalTo(0));
    }

    public void testExplicitFuzzyDistance() {
        FuzzyQuery query = asInstanceOf(FuzzyQuery.class, parse("fox~2"));
        assertThat(query.getMaxEdits(), equalTo(2));
    }

    public void testRegex() {
        assertThat(parse("/f[ao]x/"), instanceOf(RegexpQuery.class));
    }

    public void testSyntaxErrorThrows() {
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> parse("fox AND"));
        assertThat(e.getMessage(), startsWith("Invalid query [fox AND] in HIGHLIGHT:"));
    }

    public void testInvalidFuzzinessThrows() {
        // Fuzziness rejects a fractional edit distance, surfaced as an IllegalArgumentException during parsing.
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> parse("fox~0.5"));
        assertThat(e.getMessage(), startsWith("Invalid query [fox~0.5] in HIGHLIGHT:"));
    }
}
