/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.BytesRefBlock;
import org.elasticsearch.compute.data.IntBlock;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.expression.LoadFromPageEvaluator;
import org.elasticsearch.compute.test.OperatorTestCase;
import org.elasticsearch.compute.test.operator.blocksource.BytesRefBlockSourceOperator;
import org.elasticsearch.index.analysis.AnalyzerScope;
import org.elasticsearch.index.analysis.NamedAnalyzer;
import org.elasticsearch.lucene.search.uhighlight.Snippet;
import org.hamcrest.Matcher;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        HighlightConfig config = config("fox", 5, 0, 0).withExecutionContext(Map.of(), analyzer, contentTerm("fox"), CONTENT);
        return new HighlightOperator.Factory(config, List.of(new LoadFromPageEvaluator.Factory(0)));
    }

    @Override
    protected Matcher<String> expectedDescriptionOfSimple() {
        return equalTo(
            "HighlightOperator[query=fox, pre_tag=<em>, post_tag=</em>, encoder=default, number_of_fragments=5, fragment_size=0, "
                + "no_match_size=0, word_boundary=false, locale=, order_by_score=false, analyzer=null, max_analyzed_offset=-1, "
                + "fields=1]"
        );
    }

    @Override
    protected Matcher<String> expectedToStringOfSimple() {
        return equalTo(
            "HighlightOperator[query=content:fox, query=fox, pre_tag=<em>, post_tag=</em>, encoder=default, number_of_fragments=5, "
                + "fragment_size=0, no_match_size=0, word_boundary=false, locale=, order_by_score=false, analyzer=null, "
                + "max_analyzed_offset=-1, fields=[Attribute[channel=0]]]"
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
            null,
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
                config("fox", 5, 0, 0).withExecutionContext(Map.of(), analyzer, contentTerm("fox"), CONTENT),
                new ExpressionEvaluator[] { new LoadFromPageEvaluator(0) }
            )
        ) {
            IntBlock intBlock = blockFactory().newConstantIntBlockWith(1, 1);
            try {
                IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> operator.process(new Page(intBlock)));
                assertThat(e.getMessage(), startsWith("HIGHLIGHT ON fields must be [text] or [keyword]"));
            } finally {
                intBlock.close();
            }
        }
    }

    public void testPhraseHighlightsAsSingleSpan() {
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

    public void testIndexAnalyzerStemsRowTextForMatching() {
        // EnglishAnalyzer stems "rings" -> "ring", so the query term "ring" matches; StandardAnalyzer would index
        // "rings" verbatim and miss it. Proves the row text is re-analyzed with the field's own index analyzer.
        BytesRefBlock stemmed = highlightWithIndexAnalyzer(
            config("ring", 5, 0, 0),
            Map.of(CONTENT_FIELD, new EnglishAnalyzer()),
            new StandardAnalyzer(),
            contentTerm("ring"),
            "running rings"
        );
        try {
            assertThat(value(stemmed, 0), equalTo("running <em>rings</em>"));
        } finally {
            stemmed.close();
        }

        // With the default StandardAnalyzer the row text is indexed as "rings", so "ring" does not match.
        BytesRefBlock notStemmed = highlightWithIndexAnalyzer(
            config("ring", 5, 0, 0),
            Map.of(),
            new StandardAnalyzer(),
            contentTerm("ring"),
            "running rings"
        );
        try {
            assertThat(notStemmed.isNull(0), equalTo(true));
        } finally {
            notStemmed.close();
        }
    }

    public void testPerFieldIndexAnalyzersTokenizeIndependently() {
        // title uses a case-preserving whitespace analyzer, body uses the lowercasing standard analyzer. The query term
        // is capitalized "Fox", matching only the whitespace-analyzed title; the standard-analyzed body lowercases to
        // "fox" and does not match. Swapping the analyzers would flip the result, proving each field uses its own.
        Query query = new BooleanQuery.Builder().add(termQuery("title", "Fox"), BooleanClause.Occur.SHOULD)
            .add(termQuery("body", "Fox"), BooleanClause.Occur.SHOULD)
            .build();
        BytesRefBlock title = bytesRefs(List.of(List.of("Fox")));
        BytesRefBlock body = bytesRefs(List.of(List.of("Fox")));
        Map<String, Analyzer> indexAnalyzers = Map.of("title", new WhitespaceAnalyzer(), "body", new StandardAnalyzer());
        Page result = highlightFields(config("Fox", 5, 0, 0), indexAnalyzers, query, TITLE_BODY, title, body);
        try {
            BytesRefBlock highlightTitle = result.getBlock(2);
            BytesRefBlock highlightBody = result.getBlock(3);
            assertThat(value(highlightTitle, 0), equalTo("<em>Fox</em>"));
            assertThat(highlightBody.isNull(0), equalTo(true));
        } finally {
            result.releaseBlocks();
        }
    }

    public void testKeywordAnalyzerGivesWholeValueSemantics() {
        // KeywordAnalyzer emits the entire value as a single token, so only a whole-value term matches (and highlights
        // the entire value); a sub-token query does not match.
        BytesRefBlock whole = highlightWithIndexAnalyzer(
            config("New York", 0, 0, 0),
            Map.of(CONTENT_FIELD, new KeywordAnalyzer()),
            new StandardAnalyzer(),
            contentTerm("New York"),
            "New York"
        );
        try {
            assertThat(value(whole, 0), equalTo("<em>New York</em>"));
        } finally {
            whole.close();
        }

        BytesRefBlock subToken = highlightWithIndexAnalyzer(
            config("York", 0, 0, 0),
            Map.of(CONTENT_FIELD, new KeywordAnalyzer()),
            new StandardAnalyzer(),
            contentTerm("York"),
            "New York"
        );
        try {
            assertThat(subToken.isNull(0), equalTo(true));
        } finally {
            subToken.close();
        }
    }

    public void testNamedAnalyzerLeavesComposeWithoutThrowing() {
        // Regression for the composition trap: NamedAnalyzer and PerFieldAnalyzerWrapper are DelegatingAnalyzerWrappers
        // and cannot be re-wrapped by LimitTokenOffsetAnalyzer. The operator must unwrap each NamedAnalyzer leaf (both
        // per-field and default) before offset-limiting and composing, otherwise this throws at tokenStream(...).
        NamedAnalyzer english = new NamedAnalyzer("english", AnalyzerScope.GLOBAL, new EnglishAnalyzer());
        NamedAnalyzer standard = new NamedAnalyzer("standard", AnalyzerScope.GLOBAL, new StandardAnalyzer());
        BytesRefBlock result = highlightWithIndexAnalyzer(
            config("ring", 5, 0, 0),
            Map.of(CONTENT_FIELD, english),
            standard,
            contentTerm("ring"),
            "running rings"
        );
        try {
            assertThat(value(result, 0), equalTo("running <em>rings</em>"));
        } finally {
            result.close();
        }
    }

    public void testPerFieldTargetingHighlightsOnlyTheTargetedColumn() {
        Query query = termQuery("title", "fox");
        BytesRefBlock title = bytesRefs(List.of(List.of("the quick fox")));
        BytesRefBlock body = bytesRefs(List.of(List.of("a fox in the henhouse")));
        Page result = highlightFields(config("title:fox", 5, 0, 0), query, TITLE_BODY, title, body);
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
        Query query = new BooleanQuery.Builder().add(termQuery("title", "fox"), BooleanClause.Occur.MUST)
            .add(termQuery("body", "dog"), BooleanClause.Occur.MUST)
            .build();
        BytesRefBlock title = bytesRefs(List.of(List.of("the fox"), List.of("the fox")));
        BytesRefBlock body = bytesRefs(List.of(List.of("a dog"), List.of("a cat")));
        Page result = highlightFields(config("+title:fox +body:dog", 5, 0, 0), query, TITLE_BODY, title, body);
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
        Query query = new BooleanQuery.Builder().add(termQuery("title", "fox"), BooleanClause.Occur.SHOULD)
            .add(termQuery("body", "fox"), BooleanClause.Occur.SHOULD)
            .build();
        BytesRefBlock title = (BytesRefBlock) blockFactory().newConstantNullBlock(1);
        BytesRefBlock body = (BytesRefBlock) blockFactory().newConstantNullBlock(1);
        Page result = highlightFields(config("fox", 5, 0, 0), query, TITLE_BODY, title, body);
        try {
            assertThat(result.<BytesRefBlock>getBlock(2).isNull(0), equalTo(true));
            assertThat(result.<BytesRefBlock>getBlock(3).isNull(0), equalTo(true));
        } finally {
            result.releaseBlocks();
        }
    }

    private static Query contentTerm(String term) {
        return termQuery(CONTENT_FIELD, term);
    }

    private static Query termQuery(String field, String term) {
        return new TermQuery(new Term(field, term));
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
        try (
            HighlightOperator operator = new HighlightOperator(
                blockFactory(),
                config.withExecutionContext(Map.of(), new StandardAnalyzer(), query, CONTENT),
                new ExpressionEvaluator[] { new LoadFromPageEvaluator(0) }
            )
        ) {
            Page result = operator.process(new Page(input));
            BytesRefBlock highlighted = result.getBlock(result.getBlockCount() - 1);
            highlighted.incRef();
            result.releaseBlocks();
            return highlighted;
        }
    }

    // Runs the operator with one input block per ON field, using the default analyzer for every field.
    private Page highlightFields(HighlightConfig config, Query query, List<String> fieldNames, BytesRefBlock... fields) {
        return highlightFields(config, Map.of(), query, fieldNames, fields);
    }

    // Runs the operator with one input block per ON field, applying the given per-field index analyzers.
    private Page highlightFields(
        HighlightConfig config,
        Map<String, Analyzer> indexAnalyzers,
        Query query,
        List<String> fieldNames,
        BytesRefBlock... fields
    ) {
        ExpressionEvaluator[] evaluators = IntStream.range(0, fields.length)
            .mapToObj(LoadFromPageEvaluator::new)
            .toArray(ExpressionEvaluator[]::new);
        try (
            HighlightOperator operator = new HighlightOperator(
                blockFactory(),
                config.withExecutionContext(indexAnalyzers, new StandardAnalyzer(), query, fieldNames),
                evaluators
            )
        ) {
            return operator.process(new Page(fields));
        }
    }

    // Runs the operator over a single "content" field with an explicit per-field index analyzer.
    private BytesRefBlock highlightWithIndexAnalyzer(
        HighlightConfig config,
        Map<String, Analyzer> indexAnalyzers,
        Analyzer defaultAnalyzer,
        Query query,
        String text
    ) {
        try (
            HighlightOperator operator = new HighlightOperator(
                blockFactory(),
                config.withExecutionContext(indexAnalyzers, defaultAnalyzer, query, CONTENT),
                new ExpressionEvaluator[] { new LoadFromPageEvaluator(0) }
            )
        ) {
            Page result = operator.process(new Page(bytesRefs(List.of(List.of(text)))));
            BytesRefBlock highlighted = result.getBlock(result.getBlockCount() - 1);
            highlighted.incRef();
            result.releaseBlocks();
            return highlighted;
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
            null,
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
            null,
            -1
        );
    }

}
