/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.esql.highlight;

import org.apache.lucene.search.Query;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.breaker.NoopCircuitBreaker;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.BytesRefBlock;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.expression.LoadFromPageEvaluator;
import org.elasticsearch.compute.operator.HighlightConfig;
import org.elasticsearch.compute.operator.HighlightOperator;
import org.elasticsearch.core.Releasable;
import org.elasticsearch.xpack.esql.core.expression.FoldContext;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.plan.logical.HighlightOptions;
import org.elasticsearch.xpack.esql.planner.HighlightQueryBuilders;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs the production ES|QL {@link HighlightOperator}, wired the way {@code LocalExecutionPlanner#planHighlight} wires
 * it.
 * <p>
 * The query and analyzer come from {@link HighlightQueryBuilders#translate}, not from a hand-built {@code TermQuery} and
 * {@code StandardAnalyzer}. That matters: production parses the HIGHLIGHT literal as {@code query_string} and hands the
 * operator {@code Lucene.STANDARD_ANALYZER}, which is a {@code NamedAnalyzer}, and {@code HighlightOperator} branches on
 * {@code instanceof NamedAnalyzer} when choosing the analyzer it feeds the memory index. A bare {@code StandardAnalyzer}
 * is therefore not an interchangeable stand-in.
 */
public final class EsqlHighlightHarness implements Releasable {

    static final String FIELD = DslHighlightHarness.FIELD;

    private static final BlockFactory BLOCK_FACTORY = BlockFactory.builder(BigArrays.NON_RECYCLING_INSTANCE)
        .breaker(new NoopCircuitBreaker("benchmark"))
        .build();

    private final HighlightOperator operator;
    private final Page page;
    private final Query query;

    public EsqlHighlightHarness(List<String> documents, String queryTerm) {
        HighlightOptions options = HighlightOptions.from(null, FoldContext.small());
        List<String> fieldNames = List.of(FIELD);
        // No analyzer override, so this resolves to Lucene.STANDARD_ANALYZER without needing an AnalysisRegistry.
        HighlightQueryBuilders.TranslatedQuery translated = HighlightQueryBuilders.translate(
            Literal.keyword(Source.EMPTY, queryTerm),
            fieldNames,
            options.analyzerName(),
            null
        );
        this.query = translated.query();
        HighlightConfig config = new HighlightConfig(
            translated.queryText(),
            options.preTag(),
            options.postTag(),
            options.encoder(),
            options.numberOfFragments(),
            options.fragmentSize(),
            options.noMatchSize(),
            HighlightOptions.BOUNDARY_SCANNER_WORD.equals(options.boundaryScanner()),
            options.boundaryScannerLocale(),
            HighlightOptions.ORDER_SCORE.equals(options.order()),
            options.analyzerName(),
            options.maxAnalyzedOffset()
        ).withExecutionContext(translated.analyzer(), translated.query(), fieldNames);

        this.operator = new HighlightOperator(BLOCK_FACTORY, config, new ExpressionEvaluator[] { new LoadFromPageEvaluator(0) });

        var builder = BLOCK_FACTORY.newBytesRefVectorBuilder(documents.size());
        for (String document : documents) {
            builder.appendBytesRef(new BytesRef(document));
        }
        this.page = new Page(builder.build().asBlock());
    }

    /**
     * Highlights every row of the page. Returns how many rows produced a snippet.
     * <p>
     * The input page is reused across invocations, standing in for the column a {@code ValuesSourceReaderOperator} would
     * have filled in before HIGHLIGHT runs. Output blocks are left for the garbage collector rather than released,
     * because the operator returns a page that shares the reused input blocks and the block factory here neither
     * recycles nor accounts for memory.
     */
    public int highlight() {
        Page result = highlightPage();
        BytesRefBlock highlighted = result.getBlock(result.getBlockCount() - 1);
        int count = 0;
        for (int row = 0; row < highlighted.getPositionCount(); row++) {
            if (highlighted.isNull(row) == false) {
                count++;
            }
        }
        return count;
    }

    /** Snippets for one row, for the cross-engine equality check. Null when the row did not match. */
    public List<String> snippets(int row) {
        Page result = highlightPage();
        BytesRefBlock highlighted = result.getBlock(result.getBlockCount() - 1);
        if (highlighted.isNull(row)) {
            return null;
        }
        BytesRef scratch = new BytesRef();
        int first = highlighted.getFirstValueIndex(row);
        List<String> snippets = new ArrayList<>();
        for (int i = 0; i < highlighted.getValueCount(row); i++) {
            snippets.add(highlighted.getBytesRef(first + i, scratch).utf8ToString());
        }
        return snippets;
    }

    // HighlightOperator#process is protected, so drive it the way a Driver would.
    private Page highlightPage() {
        operator.addInput(page);
        return operator.getOutput();
    }

    public Query query() {
        return query;
    }

    @Override
    public void close() {
        page.releaseBlocks();
        operator.close();
    }
}
