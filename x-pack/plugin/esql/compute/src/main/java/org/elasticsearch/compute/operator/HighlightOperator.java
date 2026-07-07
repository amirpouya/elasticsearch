/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.memory.MemoryIndex;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.highlight.DefaultEncoder;
import org.apache.lucene.search.highlight.Encoder;
import org.apache.lucene.search.highlight.SimpleHTMLEncoder;
import org.apache.lucene.search.uhighlight.CustomSeparatorBreakIterator;
import org.apache.lucene.search.uhighlight.PassageFormatter;
import org.apache.lucene.search.uhighlight.SplittingBreakIterator;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.BytesRefBlock;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.NamedAnalyzer;
import org.elasticsearch.lucene.search.uhighlight.BoundedBreakIteratorScanner;
import org.elasticsearch.lucene.search.uhighlight.CustomPassageFormatter;
import org.elasticsearch.lucene.search.uhighlight.CustomUnifiedHighlighter;
import org.elasticsearch.lucene.search.uhighlight.QueryMaxAnalyzedOffset;
import org.elasticsearch.lucene.search.uhighlight.Snippet;
import org.elasticsearch.search.fetch.subphase.highlight.LimitTokenOffsetAnalyzer;

import java.io.IOException;
import java.text.BreakIterator;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Appends one highlighted keyword column per ON field.
 * <p>
 * For each row, all ON fields are indexed in one {@link MemoryIndex} under their real field names, then highlighted
 * per field with strict field matching. This preserves whole-query boolean behavior and Query DSL-style
 * {@code require_field_match}.
 * <p>
 * Uses the coordinator-side MemoryIndex path (POSTINGS offsets), like {@code TOP_SNIPPETS}.
 */
public class HighlightOperator extends AbstractPageMappingOperator {

    /** Synthetic field used only by the single-field parser entry point. */
    public static final String CONTENT_FIELD = "content";

    public record Factory(
        HighlightConfig config,
        Analyzer analyzer,
        Query query,
        List<String> fieldNames,
        List<ExpressionEvaluator.Factory> fieldEvaluatorFactories
    ) implements OperatorFactory {

        @Override
        public Operator get(DriverContext driverContext) {
            ExpressionEvaluator[] fieldEvaluators = fieldEvaluatorFactories.stream()
                .map(factory -> factory.get(driverContext))
                .toArray(ExpressionEvaluator[]::new);
            return new HighlightOperator(driverContext.blockFactory(), config, analyzer, query, fieldNames, fieldEvaluators);
        }

        @Override
        public String describe() {
            return "HighlightOperator[" + config.describe() + ", fields=" + fieldEvaluatorFactories.size() + "]";
        }
    }

    private final BlockFactory blockFactory;
    private final HighlightConfig config;
    private final Query query;
    private final List<String> fieldNames;
    private final Analyzer analyzer;
    private final Analyzer memoryIndexAnalyzer;
    private final PassageFormatter formatter;
    private final int indexMaxAnalyzedOffset;
    private final QueryMaxAnalyzedOffset queryMaxAnalyzedOffset;
    private final int highlighterNumberOfFragments;
    private final Supplier<BreakIterator> breakIteratorSupplier;
    private final ExpressionEvaluator[] fieldEvaluators;

    public HighlightOperator(
        BlockFactory blockFactory,
        HighlightConfig config,
        Analyzer analyzer,
        Query query,
        List<String> fieldNames,
        ExpressionEvaluator[] fieldEvaluators
    ) {
        this.blockFactory = blockFactory;
        this.config = config;
        this.fieldEvaluators = fieldEvaluators;
        this.analyzer = analyzer;
        // The planner builds/parses the Lucene query once; this operator only executes it.
        this.query = query;
        this.fieldNames = fieldNames;
        Encoder encoder = HighlightConfig.HTML_ENCODER.equals(config.encoder()) ? new SimpleHTMLEncoder() : new DefaultEncoder();
        this.formatter = new CustomPassageFormatter(config.preTag(), config.postTag(), encoder, config.numberOfFragments());
        // Clamp user max_analyzed_offset to the default index cap used on the coordinator.
        this.indexMaxAnalyzedOffset = IndexSettings.MAX_ANALYZED_OFFSET_SETTING.get(Settings.EMPTY);
        int configuredOffset = config.maxAnalyzedOffset();
        int queryOffset = configuredOffset < 0 ? indexMaxAnalyzedOffset : Math.min(configuredOffset, indexMaxAnalyzedOffset);
        this.queryMaxAnalyzedOffset = QueryMaxAnalyzedOffset.create(queryOffset, indexMaxAnalyzedOffset);
        // Unwrap NamedAnalyzer before LimitTokenOffsetAnalyzer wrapping.
        Analyzer indexingAnalyzer = analyzer instanceof NamedAnalyzer named ? named.analyzer() : analyzer;
        this.memoryIndexAnalyzer = new LimitTokenOffsetAnalyzer(indexingAnalyzer, queryMaxAnalyzedOffset.getNotNull());
        // number_of_fragments=0 means whole value; otherwise Lucene chooses top-N passages.
        this.highlighterNumberOfFragments = config.numberOfFragments() > 0 ? config.numberOfFragments() : Integer.MAX_VALUE - 1;
        this.breakIteratorSupplier = breakIterator(
            config.numberOfFragments(),
            config.fragmentSize(),
            config.wordBoundary(),
            config.locale()
        );
    }

    // Same break-iterator policy as DefaultHighlighter.
    private static Supplier<BreakIterator> breakIterator(int numberOfFragments, int fragmentSize, boolean wordBoundary, Locale locale) {
        if (numberOfFragments == 0) {
            // One passage per value.
            return () -> new CustomSeparatorBreakIterator(CustomUnifiedHighlighter.MULTIVAL_SEP_CHAR);
        }
        return () -> {
            BreakIterator passageIterator = wordBoundary
                ? BreakIterator.getWordInstance(locale)
                : sentenceBreakIterator(fragmentSize, locale);
            return new SplittingBreakIterator(passageIterator, CustomUnifiedHighlighter.MULTIVAL_SEP_CHAR);
        };
    }

    // Break by sentence; optionally cap sentence length with fragment_size.
    private static BreakIterator sentenceBreakIterator(int fragmentSize, Locale locale) {
        return fragmentSize > 0 ? BoundedBreakIteratorScanner.getSentence(locale, fragmentSize) : BreakIterator.getSentenceInstance(locale);
    }

    @Override
    protected Page process(Page page) {
        int rowCount = page.getPositionCount();
        int fieldCount = fieldEvaluators.length;
        BytesRefBlock[] fieldValues = new BytesRefBlock[fieldCount];
        BytesRefBlock.Builder[] builders = new BytesRefBlock.Builder[fieldCount];
        Block[] highlightedBlocks = new Block[fieldCount];
        // Reuse scratch holder for bytes extraction.
        BytesRef scratch = new BytesRef();
        // Reuse per row: joined text for each ON field.
        String[] rowText = new String[fieldCount];
        boolean success = false;
        try {
            fillFieldValues(page, fieldValues);
            initBuilders(rowCount, builders);
            for (int row = 0; row < rowCount; row++) {
                highlightRow(row, fieldValues, builders, rowText, scratch);
            }
            buildHighlightedBlocks(builders, highlightedBlocks);
            Page result = page.appendBlocks(highlightedBlocks);
            success = true;
            return result;
        } finally {
            // Release temporary blocks/builders; release built result blocks only on failure.
            Releasables.closeExpectNoException(fieldValues);
            Releasables.closeExpectNoException(builders);
            if (success == false) {
                Releasables.closeExpectNoException(highlightedBlocks);
            }
        }
    }

    private void fillFieldValues(Page page, BytesRefBlock[] fieldValues) {
        for (int field = 0; field < fieldEvaluators.length; field++) {
            Block block = fieldEvaluators[field].eval(page);
            if (block instanceof BytesRefBlock b) {
                fieldValues[field] = b;
                continue;
            }
            block.close();
            throw new IllegalArgumentException(
                "HIGHLIGHT ON fields must evaluate to keyword/text values but got [" + block.getClass().getSimpleName() + "]"
            );
        }
    }

    private void initBuilders(int rowCount, BytesRefBlock.Builder[] builders) {
        for (int field = 0; field < builders.length; field++) {
            builders[field] = blockFactory.newBytesRefBlockBuilder(rowCount);
        }
    }

    private static void buildHighlightedBlocks(BytesRefBlock.Builder[] builders, Block[] highlightedBlocks) {
        for (int field = 0; field < highlightedBlocks.length; field++) {
            highlightedBlocks[field] = builders[field].build();
        }
    }

    /** Highlights one row across all ON fields. */
    private void highlightRow(int row, BytesRefBlock[] fieldValues, BytesRefBlock.Builder[] builders, String[] rowText, BytesRef scratch) {
        boolean hasRowValues = false;
        for (int f = 0; f < fieldValues.length; f++) {
            int valueCount = fieldValues[f].getValueCount(row);
            rowText[f] = valueCount == 0 ? null : joinValues(fieldValues[f], row, valueCount, scratch);
            hasRowValues |= rowText[f] != null;
        }
        if (hasRowValues == false) {
            appendNulls(builders);
            return;
        }
        IndexSearcher searcher = createRowSearcher(rowText);
        for (int f = 0; f < builders.length; f++) {
            if (rowText[f] == null) {
                builders[f].appendNull();
                continue;
            }
            try {
                appendSnippets(builders[f], highlight(searcher, fieldNames.get(f), rowText[f]));
            } catch (IOException e) {
                throw new IllegalStateException("HIGHLIGHT failed to highlight field", e);
            }
        }
    }

    private IndexSearcher createRowSearcher(String[] rowText) {
        MemoryIndex memoryIndex = new MemoryIndex(true);
        for (int f = 0; f < rowText.length; f++) {
            String text = rowText[f];
            if (text != null) {
                memoryIndex.addField(fieldNames.get(f), text, memoryIndexAnalyzer);
            }
        }
        return memoryIndex.createSearcher();
    }

    private static void appendNulls(BytesRefBlock.Builder[] builders) {
        for (BytesRefBlock.Builder builder : builders) {
            builder.appendNull();
        }
    }

    /** Joins multi-valued field values using the highlighter multi-value separator. */
    private static String joinValues(BytesRefBlock fieldValues, int row, int valueCount, BytesRef scratch) {
        int firstValueIndex = fieldValues.getFirstValueIndex(row);
        if (valueCount == 1) {
            return fieldValues.getBytesRef(firstValueIndex, scratch).utf8ToString();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < valueCount; i++) {
            if (i > 0) {
                sb.append(CustomUnifiedHighlighter.MULTIVAL_SEP_CHAR);
            }
            sb.append(fieldValues.getBytesRef(firstValueIndex + i, scratch).utf8ToString());
        }
        return sb.toString();
    }

    private Snippet[] highlight(IndexSearcher searcher, String field, String text) throws IOException {
        UnifiedHighlighter.Builder builder = UnifiedHighlighter.builder(searcher, analyzer);
        builder.withFormatter(formatter);
        builder.withBreakIterator(breakIteratorSupplier);
        CustomUnifiedHighlighter highlighter = new CustomUnifiedHighlighter(
            builder,
            UnifiedHighlighter.OffsetSource.POSTINGS,
            null,
            "",
            field,
            query,
            config.noMatchSize(),
            highlighterNumberOfFragments,
            indexMaxAnalyzedOffset,
            queryMaxAnalyzedOffset,
            // Both must be true to keep weight-matches behavior and strict per-field matching.
            true,
            true
        );
        LeafReaderContext leaf = searcher.getIndexReader().leaves().getFirst();
        return highlighter.highlightField(leaf.reader(), 0, () -> text);
    }

    /** Appends null/single/multi-value snippets for one row. */
    private void appendSnippets(BytesRefBlock.Builder builder, Snippet[] snippets) {
        if (snippets == null || snippets.length == 0) {
            builder.appendNull();
            return;
        }
        if (config.orderByScore() && snippets.length > 1) {
            Arrays.sort(snippets, SCORE_DESCENDING);
        }
        int count = snippets.length;
        if (config.numberOfFragments() > 0) {
            count = Math.min(count, config.numberOfFragments());
        }
        if (count == 1) {
            builder.appendBytesRef(new BytesRef(snippets[0].getText()));
            return;
        }
        builder.beginPositionEntry();
        for (int i = 0; i < count; i++) {
            builder.appendBytesRef(new BytesRef(snippets[i].getText()));
        }
        builder.endPositionEntry();
    }

    // Sort by highest score first; treat NaN as lowest so fallback passages stay last.
    static final Comparator<Snippet> SCORE_DESCENDING = Comparator.comparingDouble((Snippet s) -> {
        float score = s.getScore();
        return Float.isNaN(score) ? Double.NEGATIVE_INFINITY : score;
    }).reversed();

    @Override
    public String toString() {
        return getClass().getSimpleName()
            + "[query="
            + query
            + ", "
            + config.describe()
            + ", fields="
            + Arrays.toString(fieldEvaluators)
            + "]";
    }

    @Override
    public void close() {
        Releasables.closeExpectNoException(() -> Releasables.close(fieldEvaluators), super::close);
    }
}
