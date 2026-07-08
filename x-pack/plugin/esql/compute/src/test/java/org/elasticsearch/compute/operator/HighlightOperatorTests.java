/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.Term;
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
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.BytesRefBlock;
import org.elasticsearch.compute.data.IntBlock;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.test.OperatorTestCase;
import org.elasticsearch.compute.test.operator.blocksource.BytesRefBlockSourceOperator;
import org.elasticsearch.lucene.search.uhighlight.Snippet;
import org.hamcrest.Matcher;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.startsWith;

public class HighlightOperatorTests extends OperatorTestCase {

    private static final String DEFAULT_PRE_TAG = "<em>";
    private static final String DEFAULT_POST_TAG = "</em>";
    private static final String DEFAULT_ENCODER = "default";

    private static final String CONTENT_FIELD = "content";
    private static final List<String> CONTENT = List.of(CONTENT_FIELD);
    private static final List<String> TITLE_BODY = List.of("title", "body");

    @Override
    protected SourceOperator simpleInput(BlockFactory blockFactory, int size) {
        List<BytesRef> input = IntStream.range(0, size).mapToObj(i -> new BytesRef("the fox number " + i)).toList();
        return new BytesRefBlockSourceOperator(blockFactory, input);
    }

    @Override
    protected Operator.OperatorFactory simple(SimpleOptions options) {
        Analyzer analyzer = new StandardAnalyzer();
        HighlightConfig config = config("fox", 5, 0, 0);
        Query query = contentTerm("fox");
        return new HighlightOperator.Factory(config, analyzer, query, CONTENT, List.of(dc -> identityEvaluator()));
    }

    @Override
    protected Matcher<String> expectedDescriptionOfSimple() {
        return equalTo(
            "HighlightOperator[query=fox, pre_tag=<em>, post_tag=</em>, encoder=default, number_of_fragments=5, fragment_size=0, "
                + "no_match_size=0, word_boundary=false, locale=, order_by_score=false, max_analyzed_offset=-1, fields=1]"
        );
    }

    @Override
    protected Matcher<String> expectedToStringOfSimple() {
        return equalTo(
            "HighlightOperator[query=content:fox, query=fox, pre_tag=<em>, post_tag=</em>, encoder=default, number_of_fragments=5, "
                + "fragment_size=0, no_match_size=0, word_boundary=false, locale=, order_by_score=false, "
                + "max_analyzed_offset=-1, fields=[identity]]"
        );
    }

    @Override
    protected void assertSimpleOutput(List<Page> input, List<Page> results) {
        BytesRef scratch = new BytesRef();
        int row = 0;
        for (Page page : results) {
            BytesRefBlock highlighted = page.getBlock(page.getBlockCount() - 1);
            for (int i = 0; i < page.getPositionCount(); i++) {
                String value = highlighted.getBytesRef(highlighted.getFirstValueIndex(i), scratch).utf8ToString();
                assertThat(value, equalTo("the <em>fox</em> number " + row));
                row++;
            }
        }
    }

    public void testNoMatchYieldsNull() {
        BytesRefBlock result = highlightSingle(config("nonexistent", 5, 0, 0), "a plain sentence");
        try {
            assertThat(result.isNull(0), equalTo(true));
        } finally {
            result.close();
        }
    }

    public void testNoMatchSizeReturnsLeadingText() {
        BytesRefBlock result = highlightSingle(config("nonexistent", 5, 0, 200), "Gardens and flowers bloom in spring.");
        try {
            assertThat(value(result, 0), equalTo("Gardens and flowers bloom in spring."));
        } finally {
            result.close();
        }
    }

    public void testEmptyQueryHasNoTermsAndDoesNotMatch() {
        // Lucene's query parser throws on blank input, so the planner short-circuits it to a MatchNoDocsQuery;
        // with the default no_match_size = 0 the row yields null.
        BytesRefBlock result = highlightSingle(config("", 5, 0, 0), new MatchNoDocsQuery("HIGHLIGHT query is empty"), "any text here");
        try {
            assertThat(result.isNull(0), equalTo(true));
        } finally {
            result.close();
        }
    }

    public void testMultiValuedFieldHighlightsEachValueInOrder() {
        BytesRefBlock input = bytesRefs(List.of(List.of("Senior Team Lead", "Lead Architect")));
        BytesRefBlock result = highlight(config("lead", 5, 0, 0), input);
        try {
            assertThat(result.getValueCount(0), equalTo(2));
            int first = result.getFirstValueIndex(0);
            BytesRef scratch = new BytesRef();
            assertThat(result.getBytesRef(first, scratch).utf8ToString(), equalTo("Senior Team <em>Lead</em>"));
            assertThat(result.getBytesRef(first + 1, scratch).utf8ToString(), equalTo("<em>Lead</em> Architect"));
        } finally {
            result.close();
        }
    }

    public void testNumberOfFragmentsSelectsBestScoringInDocumentOrder() {
        // number_of_fragments is handed to Lucene as maxPassages, so it keeps the best N passages by score (Query DSL
        // parity) and returns them in document order. The first sentence has the fewest matches and is dropped; the two
        // higher-scoring sentences remain, still in document order. (The pre-V4 operator kept the first N in document
        // order instead, which would have returned the first two sentences.)
        String text = "One fox. Two fox fox. Three fox fox fox.";
        BytesRefBlock result = highlightSingle(config("fox", 2, 0, 0), text);
        try {
            assertThat(result.getValueCount(0), equalTo(2));
            int first = result.getFirstValueIndex(0);
            BytesRef scratch = new BytesRef();
            assertThat(result.getBytesRef(first, scratch).utf8ToString(), equalTo("Two <em>fox</em> <em>fox</em>."));
            assertThat(result.getBytesRef(first + 1, scratch).utf8ToString(), equalTo("Three <em>fox</em> <em>fox</em> <em>fox</em>."));
        } finally {
            result.close();
        }
    }

    public void testFragmentSizeBoundsLongSentence() {
        String text = "Elasticsearch powers fast search across very many documents and shards in a single cluster.";
        BytesRefBlock result = highlightSingle(config("elasticsearch", 5, 20, 0), text);
        try {
            // With a 20-char bound the matched fragment is shorter than the full sentence.
            assertThat(value(result, 0).length(), lessThan(text.length() + "<em></em>".length()));
            assertThat(value(result, 0).contains("<em>Elasticsearch</em>"), equalTo(true));
        } finally {
            result.close();
        }
    }

    public void testHtmlEncoderEscapesMarkup() {
        String text = "Use <b>bold</b> tags & special chars with the Ring.";
        HighlightConfig config = new HighlightConfig(
            "ring",
            DEFAULT_PRE_TAG,
            DEFAULT_POST_TAG,
            HighlightConfig.HTML_ENCODER,
            5,
            0,
            0,
            false,
            Locale.ROOT,
            false,
            -1
        );
        BytesRefBlock result = highlightSingle(config, text);
        try {
            assertThat(value(result, 0), equalTo("Use &lt;b&gt;bold&lt;&#x2F;b&gt; tags &amp; special chars with the <em>Ring</em>."));
        } finally {
            result.close();
        }
    }

    public void testWordBoundaryFragments() {
        String text = "Elasticsearch powers fast search across very many documents and shards in a single cluster.";
        BytesRefBlock result = highlight(config("elasticsearch", 5, 20, 0, true, false), bytesRefs(List.of(List.of(text))));
        try {
            // The word scanner ignores fragment_size and breaks on word boundaries, so the fragment is short.
            assertThat(value(result, 0).contains("<em>Elasticsearch</em>"), equalTo(true));
            assertThat(value(result, 0).length(), lessThan(text.length() + "<em></em>".length()));
        } finally {
            result.close();
        }
    }

    public void testOrderByScoreReturnsBestFragmentFirst() {
        // The second sentence has two matches, so it scores higher and must come first when ordering by score.
        String text = "Search is fast. Fast search powers fast results. Indexing is simple.";
        BytesRefBlock result = highlight(config("fast", 5, 0, 0, false, true), bytesRefs(List.of(List.of(text))));
        try {
            int first = result.getFirstValueIndex(0);
            BytesRef scratch = new BytesRef();
            assertThat(result.getBytesRef(first, scratch).utf8ToString(), startsWith("<em>Fast</em> search powers <em>fast</em> results."));
        } finally {
            result.close();
        }
    }

    public void testOrderByScoreWithSingleFragmentReturnsOnlyBest() {
        String text = "Search is fast. Indexing is fast. Fast search powers fast results. Queries are fast.";
        BytesRefBlock result = highlight(config("fast", 1, 0, 0, false, true), bytesRefs(List.of(List.of(text))));
        try {
            assertThat(result.getValueCount(0), equalTo(1));
            assertThat(value(result, 0), equalTo("<em>Fast</em> search powers <em>fast</em> results."));
        } finally {
            result.close();
        }
    }

    // The no-match fallback passage carries a NaN score, which must sort last rather than first under order=score.
    public void testScoreDescendingTreatsNaNAsLowest() {
        Snippet best = new Snippet("best", 5.0f, true);
        Snippet worst = new Snippet("worst", 1.0f, true);
        Snippet noMatch = new Snippet("no-match-fallback", Float.NaN, false);
        Snippet[] snippets = { noMatch, worst, best };
        Arrays.sort(snippets, HighlightOperator.SCORE_DESCENDING);
        assertThat(Arrays.stream(snippets).map(Snippet::getText).toList(), contains("best", "worst", "no-match-fallback"));
    }

    // Equal scores keep document order because Arrays.sort is stable and the comparator returns 0 on ties.
    public void testScoreDescendingKeepsDocumentOrderOnTies() {
        Snippet first = new Snippet("first", 2.0f, true);
        Snippet second = new Snippet("second", 2.0f, true);
        Snippet third = new Snippet("third", 2.0f, true);
        Snippet[] snippets = { first, second, third };
        Arrays.sort(snippets, HighlightOperator.SCORE_DESCENDING);
        assertThat(Arrays.stream(snippets).map(Snippet::getText).toList(), contains("first", "second", "third"));
    }

    public void testNonBytesRefFieldThrows() {
        Analyzer analyzer = new StandardAnalyzer();
        try (
            HighlightOperator operator = new HighlightOperator(
                blockFactory(),
                config("fox", 5, 0, 0),
                analyzer,
                contentTerm("fox"),
                CONTENT,
                new ExpressionEvaluator[] { identityEvaluator() }
            )
        ) {
            IntBlock intBlock = blockFactory().newConstantIntBlockWith(1, 1);
            try {
                IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> operator.process(new Page(intBlock)));
                assertThat(e.getMessage(), startsWith("HIGHLIGHT ON fields must evaluate to keyword/text values"));
            } finally {
                intBlock.close();
            }
        }
    }

    public void testPhraseHighlightsAsSingleSpan() {
        // A quoted phrase becomes a PhraseQuery; weight-matches mode wraps the whole matched span in one <em>…</em>.
        BytesRefBlock result = highlightSingle(
            config("\"quick brown fox\"", 5, 0, 0),
            new PhraseQuery(CONTENT_FIELD, "quick", "brown", "fox"),
            "The quick brown fox jumps over the lazy dog."
        );
        try {
            assertThat(value(result, 0), equalTo("The <em>quick brown fox</em> jumps over the lazy dog."));
        } finally {
            result.close();
        }
    }

    public void testAdjacentTermsStillWrapIndependently() {
        // Unquoted adjacent terms are an OR of independent term queries, so each match wraps on its own.
        BytesRefBlock result = highlightSingle(
            config("quick brown fox", 5, 0, 0),
            anyOf(contentTerm("quick"), contentTerm("brown"), contentTerm("fox")),
            "The quick brown fox jumps over the lazy dog."
        );
        try {
            assertThat(value(result, 0), equalTo("The <em>quick</em> <em>brown</em> <em>fox</em> jumps over the lazy dog."));
        } finally {
            result.close();
        }
    }

    public void testWildcardQuery() {
        BytesRefBlock result = highlightSingle(config("f?x", 5, 0, 0), new WildcardQuery(new Term(CONTENT_FIELD, "f?x")), "The fox jumps");
        try {
            assertThat(value(result, 0), equalTo("The <em>fox</em> jumps"));
        } finally {
            result.close();
        }
    }

    public void testLeadingWildcardAllowed() {
        BytesRefBlock result = highlightSingle(config("*ox", 5, 0, 0), new WildcardQuery(new Term(CONTENT_FIELD, "*ox")), "The fox jumps");
        try {
            assertThat(value(result, 0), equalTo("The <em>fox</em> jumps"));
        } finally {
            result.close();
        }
    }

    public void testPrefixQuery() {
        BytesRefBlock result = highlightSingle(
            config("fo*", 5, 0, 0),
            new PrefixQuery(new Term(CONTENT_FIELD, "fo")),
            "The fox and the fog"
        );
        try {
            assertThat(value(result, 0), equalTo("The <em>fox</em> and the <em>fog</em>"));
        } finally {
            result.close();
        }
    }

    public void testFuzzyQueryHighlightsMatch() {
        // "quikc" is one transposition from "quick".
        BytesRefBlock result = highlightSingle(
            config("quikc~", 5, 0, 0),
            new FuzzyQuery(new Term(CONTENT_FIELD, "quikc"), 1),
            "The quick fox"
        );
        try {
            assertThat(value(result, 0), equalTo("The <em>quick</em> fox"));
        } finally {
            result.close();
        }
    }

    public void testFuzzyQueryWithZeroEditsRequiresExactMatch() {
        BytesRefBlock result = highlightSingle(config("fx~", 5, 0, 0), new FuzzyQuery(new Term(CONTENT_FIELD, "fx"), 0), "The fox jumps");
        try {
            assertThat(result.isNull(0), equalTo(true));
        } finally {
            result.close();
        }
    }

    public void testExplicitFuzzyEdits() {
        // "fux~1" allows one edit, matching "fox".
        BytesRefBlock result = highlightSingle(
            config("fux~1", 5, 0, 0),
            new FuzzyQuery(new Term(CONTENT_FIELD, "fux"), 1),
            "The fox jumps"
        );
        try {
            assertThat(value(result, 0), equalTo("The <em>fox</em> jumps"));
        } finally {
            result.close();
        }
    }

    public void testRegexQuery() {
        BytesRefBlock result = highlightSingle(
            config("/f[ao]x/", 5, 0, 0),
            new RegexpQuery(new Term(CONTENT_FIELD, "f[ao]x")),
            "The fox jumps"
        );
        try {
            assertThat(value(result, 0), equalTo("The <em>fox</em> jumps"));
        } finally {
            result.close();
        }
    }

    public void testBooleanAndRequiresBothTerms() {
        Query query = allOf(contentTerm("fox"), contentTerm("dog"));
        BytesRefBlock both = highlightSingle(config("fox AND dog", 5, 0, 0), query, "The fox and the dog play.");
        try {
            assertThat(value(both, 0), equalTo("The <em>fox</em> and the <em>dog</em> play."));
        } finally {
            both.close();
        }
        BytesRefBlock missing = highlightSingle(config("fox AND dog", 5, 0, 0), query, "The fox jumps");
        try {
            assertThat(missing.isNull(0), equalTo(true));
        } finally {
            missing.close();
        }
    }

    public void testBooleanNotExcludes() {
        // A prohibited term that is present makes the whole query not match the row, so nothing highlights. This pins the
        // strict boolean semantics that weight-matches mode enforces.
        Query query = booleanQuery(
            new BooleanClause(contentTerm("fox"), BooleanClause.Occur.MUST),
            new BooleanClause(contentTerm("dog"), BooleanClause.Occur.MUST_NOT)
        );
        BytesRefBlock present = highlightSingle(config("fox -dog", 5, 0, 0), query, "The fox and the dog play.");
        try {
            assertThat(present.isNull(0), equalTo(true));
        } finally {
            present.close();
        }
        BytesRefBlock absent = highlightSingle(config("fox -dog", 5, 0, 0), query, "The fox jumps");
        try {
            assertThat(value(absent, 0), equalTo("The <em>fox</em> jumps"));
        } finally {
            absent.close();
        }
    }

    public void testRequiredOperator() {
        // "+fox" is required, "cat" is optional; the row matches on the required term alone.
        Query query = booleanQuery(
            new BooleanClause(contentTerm("fox"), BooleanClause.Occur.MUST),
            new BooleanClause(contentTerm("cat"), BooleanClause.Occur.SHOULD)
        );
        BytesRefBlock result = highlightSingle(config("+fox cat", 5, 0, 0), query, "The fox jumps");
        try {
            assertThat(value(result, 0), equalTo("The <em>fox</em> jumps"));
        } finally {
            result.close();
        }
    }

    public void testGrouping() {
        Query query = allOf(anyOf(contentTerm("fox"), contentTerm("cat")), contentTerm("jumps"));
        BytesRefBlock result = highlightSingle(config("(fox OR cat) AND jumps", 5, 0, 0), query, "The fox jumps");
        try {
            assertThat(value(result, 0), equalTo("The <em>fox</em> <em>jumps</em>"));
        } finally {
            result.close();
        }
    }

    public void testFieldQualifiedTermDoesNotHighlight() {
        // The single ON field is "content" and require_field_match is on, so a term qualified on another field targets
        // a field that is not indexed and never matches (Query DSL require_field_match: true parity).
        BytesRefBlock result = highlightSingle(config("title:fox", 5, 0, 0), termQuery("title", "fox"), "The fox jumps");
        try {
            assertThat(result.isNull(0), equalTo(true));
        } finally {
            result.close();
        }
    }

    public void testPerFieldTargetingHighlightsOnlyTheTargetedColumn() {
        // MATCH(title, "fox") targets only the title field: the title column highlights and the body column stays null,
        // even though "fox" also appears in body. This is the design's §3.1 column-population contract.
        Analyzer analyzer = new StandardAnalyzer();
        Query query = termQuery("title", "fox");
        BytesRefBlock title = bytesRefs(List.of(List.of("the quick fox")));
        BytesRefBlock body = bytesRefs(List.of(List.of("a fox in the henhouse")));
        Page result = highlightFields(config("title:fox", 5, 0, 0), query, TITLE_BODY, analyzer, title, body);
        try {
            BytesRefBlock highlightTitle = result.getBlock(2);
            BytesRefBlock highlightBody = result.getBlock(3);
            assertThat(value(highlightTitle, 0), equalTo("the quick <em>fox</em>"));
            assertThat(highlightBody.isNull(0), equalTo(true));
        } finally {
            result.releaseBlocks();
        }
    }

    public void testCrossFieldConjunctionHighlightsWholeRowOrNothing() {
        // MATCH(title,"fox") AND MATCH(body,"dog"): row 0 satisfies both clauses, so both columns highlight; row 1 is
        // missing "dog" in body, so the conjunction fails and NEITHER column highlights (whole-query boolean semantics
        // under weight-matches, matching Query DSL highlight_query).
        Analyzer analyzer = new StandardAnalyzer();
        Query query = allOf(termQuery("title", "fox"), termQuery("body", "dog"));
        BytesRefBlock title = bytesRefs(List.of(List.of("the fox"), List.of("the fox")));
        BytesRefBlock body = bytesRefs(List.of(List.of("a dog"), List.of("a cat")));
        Page result = highlightFields(config("+title:fox +body:dog", 5, 0, 0), query, TITLE_BODY, analyzer, title, body);
        try {
            BytesRefBlock highlightTitle = result.getBlock(2);
            BytesRefBlock highlightBody = result.getBlock(3);
            assertThat(value(highlightTitle, 0), equalTo("the <em>fox</em>"));
            assertThat(value(highlightBody, 0), equalTo("a <em>dog</em>"));
            assertThat(highlightTitle.isNull(1), equalTo(true));
            assertThat(highlightBody.isNull(1), equalTo(true));
        } finally {
            result.releaseBlocks();
        }
    }

    public void testRowWithAllNullFieldsYieldsNullEverywhere() {
        // A row where neither ON field has any value must emit null for every highlight column without building an index.
        Analyzer analyzer = new StandardAnalyzer();
        Query query = anyOf(termQuery("title", "fox"), termQuery("body", "fox"));
        BytesRefBlock title = nulls(1);
        BytesRefBlock body = nulls(1);
        Page result = highlightFields(config("fox", 5, 0, 0), query, TITLE_BODY, analyzer, title, body);
        try {
            assertThat(result.<BytesRefBlock>getBlock(2).isNull(0), equalTo(true));
            assertThat(result.<BytesRefBlock>getBlock(3).isNull(0), equalTo(true));
        } finally {
            result.releaseBlocks();
        }
    }

    public void testNoTermsQueryMatchesNothing() {
        BytesRefBlock result = highlight(
            config("the", 5, 0, 0),
            new MatchNoDocsQuery("HIGHLIGHT query produced no terms"),
            bytesRefs(List.of(List.of("the fox")))
        );
        try {
            assertThat(result.isNull(0), equalTo(true));
        } finally {
            result.close();
        }
    }

    public void testNoMatchSizeWithValidNonMatchingQuery() {
        // A valid phrase that doesn't match still returns the leading text: no_match_size is independent of weight-matches.
        BytesRefBlock result = highlightSingle(
            config("\"zebra stripes\"", 5, 0, 200),
            new PhraseQuery(CONTENT_FIELD, "zebra", "stripes"),
            "Gardens bloom."
        );
        try {
            assertThat(value(result, 0), equalTo("Gardens bloom."));
        } finally {
            result.close();
        }
    }

    private static Query contentTerm(String term) {
        return termQuery(CONTENT_FIELD, term);
    }

    private static Query termQuery(String field, String term) {
        return new TermQuery(new Term(field, term));
    }

    private static Query anyOf(Query... queries) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (Query query : queries) {
            builder.add(query, BooleanClause.Occur.SHOULD);
        }
        return builder.build();
    }

    private static Query allOf(Query... queries) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (Query query : queries) {
            builder.add(query, BooleanClause.Occur.MUST);
        }
        return builder.build();
    }

    private static Query booleanQuery(BooleanClause... clauses) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (BooleanClause clause : clauses) {
            builder.add(clause);
        }
        return builder.build();
    }

    private BytesRefBlock highlightSingle(HighlightConfig config, String text) {
        return highlightSingle(config, contentTerm(config.queryText()), text);
    }

    private BytesRefBlock highlightSingle(HighlightConfig config, Query query, String text) {
        return highlight(config, query, bytesRefs(List.of(List.of(text))));
    }

    private BytesRefBlock highlight(HighlightConfig config, BytesRefBlock input) {
        return highlight(config, contentTerm(config.queryText()), input);
    }

    private BytesRefBlock highlight(HighlightConfig config, Query query, BytesRefBlock input) {
        return highlight(config, query, input, new StandardAnalyzer());
    }

    private BytesRefBlock highlight(HighlightConfig config, Query query, BytesRefBlock input, Analyzer analyzer) {
        try (
            HighlightOperator operator = new HighlightOperator(
                blockFactory(),
                config,
                analyzer,
                query,
                CONTENT,
                new ExpressionEvaluator[] { identityEvaluator() }
            )
        ) {
            Page result = operator.process(new Page(input));
            BytesRefBlock highlighted = result.getBlock(result.getBlockCount() - 1);
            highlighted.incRef();
            result.releaseBlocks();
            return highlighted;
        }
    }

    // Runs the operator over one block per ON field (channel f feeds field f) and returns the result page, whose last
    // fieldNames.size() blocks are the highlight columns in ON order.
    private Page highlightFields(HighlightConfig config, Query query, List<String> fieldNames, Analyzer analyzer, BytesRefBlock... fields) {
        ExpressionEvaluator[] evaluators = IntStream.range(0, fields.length)
            .mapToObj(HighlightOperatorTests::channelEvaluator)
            .toArray(ExpressionEvaluator[]::new);
        try (HighlightOperator operator = new HighlightOperator(blockFactory(), config, analyzer, query, fieldNames, evaluators)) {
            return operator.process(new Page(fields));
        }
    }

    private BytesRefBlock nulls(int count) {
        try (BytesRefBlock.Builder builder = blockFactory().newBytesRefBlockBuilder(count)) {
            for (int i = 0; i < count; i++) {
                builder.appendNull();
            }
            return builder.build();
        }
    }

    private static String value(BytesRefBlock block, int position) {
        return block.getBytesRef(block.getFirstValueIndex(position), new BytesRef()).utf8ToString();
    }

    private BytesRefBlock bytesRefs(List<List<String>> rows) {
        try (BytesRefBlock.Builder builder = blockFactory().newBytesRefBlockBuilder(rows.size())) {
            for (List<String> row : rows) {
                if (row.size() == 1) {
                    builder.appendBytesRef(new BytesRef(row.get(0)));
                } else {
                    builder.beginPositionEntry();
                    for (String value : row) {
                        builder.appendBytesRef(new BytesRef(value));
                    }
                    builder.endPositionEntry();
                }
            }
            return builder.build();
        }
    }

    private static HighlightConfig config(String queryText, int fragments, int fragmentSize, int noMatchSize) {
        return new HighlightConfig(
            queryText,
            DEFAULT_PRE_TAG,
            DEFAULT_POST_TAG,
            DEFAULT_ENCODER,
            fragments,
            fragmentSize,
            noMatchSize,
            false,
            Locale.ROOT,
            false,
            -1
        );
    }

    private static HighlightConfig config(
        String queryText,
        int fragments,
        int fragmentSize,
        int noMatchSize,
        boolean wordBoundary,
        boolean orderByScore
    ) {
        return new HighlightConfig(
            queryText,
            DEFAULT_PRE_TAG,
            DEFAULT_POST_TAG,
            DEFAULT_ENCODER,
            fragments,
            fragmentSize,
            noMatchSize,
            wordBoundary,
            Locale.ROOT,
            orderByScore,
            -1
        );
    }

    // Returns the input block unchanged, so the operator highlights channel 0 directly.
    private static ExpressionEvaluator identityEvaluator() {
        return new ExpressionEvaluator() {
            @Override
            public Block eval(Page page) {
                Block block = page.getBlock(0);
                block.incRef();
                return block;
            }

            @Override
            public long baseRamBytesUsed() {
                return 0;
            }

            @Override
            public void close() {}

            @Override
            public String toString() {
                return "identity";
            }
        };
    }

    // Feeds one input channel to one ON field, so multi-field tests can wire block[f] to fieldNames.get(f).
    private static ExpressionEvaluator channelEvaluator(int channel) {
        return new ExpressionEvaluator() {
            @Override
            public Block eval(Page page) {
                Block block = page.getBlock(channel);
                block.incRef();
                return block;
            }

            @Override
            public long baseRamBytesUsed() {
                return 0;
            }

            @Override
            public void close() {}

            @Override
            public String toString() {
                return "channel" + channel;
            }
        };
    }
}
