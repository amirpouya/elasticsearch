/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.esql.highlight;

import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter.OffsetSource;
import org.elasticsearch.benchmark.Utils;
import org.elasticsearch.benchmark.esql.highlight.DslHighlightHarness.Variant;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Compares the per-document cost of the ES|QL {@code HIGHLIGHT} command against Query DSL {@code unified} highlighting,
 * on identical documents, queries, and options.
 *
 * <p>The point is to split the gap into the part that is avoidable engineering and the part that is architectural.
 * {@code esql} against {@code dslAnalysis} is apples to apples: a default {@code text} field gives Query DSL the
 * {@code ANALYSIS} offset source, which Lucene serves with {@code MemoryIndexOffsetStrategy} — the same per-document
 * memory index {@code HighlightOperator} builds, but reusing it across documents and indexing only tokens the query
 * could match. That whole gap is closable without changing the architecture. {@code esql} against {@code dslPostings} is
 * the total gap, where offsets come from the segment and no analysis happens at all; the difference between the two
 * ratios is the part that needs highlighting against shard data.
 *
 * <p>This measures per-document highlight cost, not request latency. Two things it deliberately leaves out: ES|QL
 * highlights up to {@code LIMIT} rows per driver (default 1000) where Query DSL highlights about {@code size} hits
 * (default 10), and neither arm pays for transport, source decoding or field loading. Do not quote these numbers as
 * request latency.
 *
 * <p>See {@code docs/internal/esql-highlight-vs-query-dsl-benchmark.md} for the full design, and
 * {@code HIGHLIGHT_PERF_PLAN.md} for the optimisation work these numbers inform.
 */
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 7, time = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class HighlightEsqlVsQueryDslBenchmark {

    static {
        Utils.configureBenchmarkLogging();
    }

    /**
     * Documents per invocation. Fixed rather than adaptive: production sizes ES|QL pages as
     * {@code max(32, 262144 / estimatedRowSize)}, which would shrink as {@code textTokens} grows and confound the
     * comparison. {@code HighlightOperator} keeps no state across pages, so a fixed size costs nothing in fidelity.
     * The value matches {@code HighlightAtRuntimeBenchmark} so its published baseline cross-checks this harness.
     */
    private static final int PAGE_SIZE = 128;

    /** Documents used for the cross-engine snippet comparison. Small, because it runs all four engines. */
    private static final int SELF_TEST_DOCS = 8;

    @Param({ "16", "128", "1024", "8192" })
    public int textTokens;

    @Param({ "esql", "dslAnalysis", "dslPostings", "dslTermVectors" })
    public String engine;

    @Param({ "hit", "miss" })
    public String outcome;

    private EsqlHighlightHarness esql;
    private DslHighlightHarness dsl;
    private int expectedMatches;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        List<String> documents = HighlightCorpus.documents(PAGE_SIZE, textTokens, 1);
        boolean hit = "hit".equals(outcome);
        String term = hit ? HighlightCorpus.HIT_TERM : HighlightCorpus.MISS_TERM;
        expectedMatches = hit ? PAGE_SIZE : 0;

        switch (engine) {
            case "esql" -> esql = new EsqlHighlightHarness(documents, term);
            case "dslAnalysis" -> dsl = new DslHighlightHarness(Variant.ANALYSIS, documents, term);
            case "dslPostings" -> dsl = new DslHighlightHarness(Variant.POSTINGS, documents, term);
            case "dslTermVectors" -> dsl = new DslHighlightHarness(Variant.TERM_VECTORS, documents, term);
            default -> throw new IllegalArgumentException("unknown engine: " + engine);
        }

        selfTest(documents, term);
    }

    /**
     * Every way this benchmark can lie is silent, so check it before measuring.
     *
     * <p>A wrong mapping key would make {@code dslPostings} resolve to {@code ANALYSIS} and the benchmark would report a
     * fabricated architectural gap while looking perfectly healthy. A query that contributed no terms for the
     * highlighted field would make Lucene pick a no-op strategy and short-circuit the whole call, making Query DSL look
     * infinitely fast on misses. And if the two engines disagree on output, there is nothing to compare in the first
     * place.
     */
    private void selfTest(List<String> documents, String term) throws IOException {
        int matched = runHighlight();
        if (matched != expectedMatches) {
            throw new AssertionError("[" + engine + "/" + outcome + "] highlighted " + matched + " documents, expected " + expectedMatches);
        }

        List<String> slice = documents.subList(0, Math.min(SELF_TEST_DOCS, documents.size()));
        try (EsqlHighlightHarness reference = new EsqlHighlightHarness(slice, term)) {
            for (Variant variant : Variant.values()) {
                try (DslHighlightHarness candidate = new DslHighlightHarness(variant, slice, term)) {
                    checkOffsetStrategyInputs(variant, candidate, reference);
                    checkSnippetsMatch(variant, candidate, reference, slice.size());
                }
            }
        }
    }

    /**
     * Pins the three inputs that decide which offset strategy Lucene uses, plus the query the two engines share.
     *
     * <p>The resolved strategy itself is unreachable — {@code CustomUnifiedHighlighter} keeps its field highlighter
     * private, and reflecting into an exported package of a named module would need {@code --add-opens} — so this checks
     * what determines it instead. {@code ANALYSIS} only resolves to {@code MemoryIndexOffsetStrategy} while
     * weight-matches is on, and {@code CustomUnifiedHighlighter} strips that flag when {@code require_field_match} is
     * false or the query shape is unsupported, at which point Lucene silently switches to
     * {@code TokenStreamOffsetStrategy} and the arm stops being the comparison we wanted.
     */
    private static void checkOffsetStrategyInputs(Variant variant, DslHighlightHarness dsl, EsqlHighlightHarness esql) {
        OffsetSource resolved = dsl.resolvedOffsetSource();
        if (resolved != variant.expectedOffsetSource()) {
            throw new AssertionError(
                "[" + variant + "] resolved offset source " + resolved + ", expected " + variant.expectedOffsetSource()
            );
        }
        if (dsl.weightMatchesEnabled() == false) {
            throw new AssertionError("[" + variant + "] weight matches is disabled, so ANALYSIS would not use a memory index");
        }
        if (dsl.requireFieldMatch() == false) {
            throw new AssertionError("[" + variant + "] require_field_match is false, which strips the weight-matches flag");
        }
        if (dsl.query() instanceof TermQuery == false) {
            throw new AssertionError("[" + variant + "] expected a TermQuery, got " + dsl.query().getClass().getSimpleName());
        }
        if (esql.query().equals(dsl.query()) == false) {
            throw new AssertionError("[" + variant + "] ES|QL query " + esql.query() + " differs from Query DSL query " + dsl.query());
        }
    }

    /**
     * Asserts both engines produce byte-identical snippets. This is what licenses the comparison at all. Single-valued
     * text under the analysis limit should agree exactly; a divergence here is a correctness finding to report, not
     * something to loosen the assertion for.
     */
    private static void checkSnippetsMatch(Variant variant, DslHighlightHarness dsl, EsqlHighlightHarness esql, int documents)
        throws IOException {
        for (int doc = 0; doc < documents; doc++) {
            List<String> fromEsql = esql.snippets(doc);
            List<String> fromDsl = dsl.snippets(doc);
            if (Objects.equals(fromEsql, fromDsl) == false) {
                throw new AssertionError(
                    "[" + variant + "] document " + doc + " snippets differ:\n  ES|QL: " + fromEsql + "\n  DSL:   " + fromDsl
                );
            }
        }
    }

    private int runHighlight() throws IOException {
        return esql != null ? esql.highlight() : dsl.highlight();
    }

    @Benchmark
    @OperationsPerInvocation(PAGE_SIZE)
    public void highlight(Blackhole bh) throws IOException {
        bh.consume(runHighlight());
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (esql != null) {
            esql.close();
        }
        if (dsl != null) {
            dsl.close();
        }
    }
}
