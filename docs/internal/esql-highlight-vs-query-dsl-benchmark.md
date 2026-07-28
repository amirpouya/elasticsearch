# Benchmarking ES|QL HIGHLIGHT against Query DSL highlighting

Design for a JMH benchmark that measures the per-document cost of the ES|QL `HIGHLIGHT`
command against Query DSL `unified` highlighting, on identical documents, queries, and
options.

Status: design approved, not yet implemented.
Date: 2026-07-28.

## 1. The question

`HIGHLIGHT` shipped with "performance on par with Query DSL" as a must-have, and that goal
is not met. We do not currently know how much of the gap is avoidable engineering and how
much is architectural, so we cannot answer whether Stage 2 (highlighting against shard data
instead of re-analysing rows) belongs on the roadmap.

This benchmark separates the two. It produces one number for the gap that can be closed
without changing the architecture, and one for the gap that cannot.

It deliberately does **not** measure request latency. See [section 9](#9-out-of-scope).

## 2. Why the two systems are comparable

Query DSL highlighting on a default `text` field is the *same algorithm* ES|QL uses. This is
the finding the whole design rests on, so it is worth spelling out.

`DefaultHighlighter` chooses the offset source from the mapping:

```java
// server/src/main/java/org/elasticsearch/search/fetch/subphase/highlight/DefaultHighlighter.java:248-260
protected static OffsetSource getOffsetSource(FetchContext fetchContext, MappedFieldType fieldType) {
    if (fetchContext.sourceLoader().reordersFieldValues()) {
        return OffsetSource.ANALYSIS;
    }
    TextSearchInfo tsi = fieldType.getTextSearchInfo();
    if (tsi.hasOffsets()) { ... }
    ...
    return OffsetSource.ANALYSIS;
}
```

A default `text` field is indexed `DOCS_AND_FREQS_AND_POSITIONS`, so `hasOffsets()` is false
and the answer is `ANALYSIS`. Lucene then resolves `ANALYSIS` to a strategy, and because ES
leaves weight-matches enabled (`IndexSettings.isWeightMatchesEnabled()`), the branch taken is
`MemoryIndexOffsetStrategy` (`UnifiedHighlighter.getOffsetStrategy`, Lucene 10.5).

`MemoryIndexOffsetStrategy` builds a `MemoryIndex` per document, exactly like
`HighlightOperator`. The difference is that Lucene does it well:

- the `MemoryIndex` and its `LeafReader` are built once in the constructor and `reset()` per
  document, rather than allocated per row
  (`MemoryIndexOffsetStrategy:45-53` versus `HighlightOperator.createRowSearcher:217-225`);
- the token stream is wrapped in a keep-word filter so only tokens the query could match are
  indexed (`MemoryIndexOffsetStrategy:101-121`).

The strategy is built once per `CustomUnifiedHighlighter`
(`CustomUnifiedHighlighter:132`, into the `private final fieldHighlighter`), and
`DefaultHighlighter` caches one highlighter per field in `fieldContext.cache`
(`DefaultHighlighter:62-70`), which lives for the whole search. So on the DSL side that
`MemoryIndex` is allocated once per field per request and reset per document, against once
per row in ES|QL.

Those two differences are precisely Phase 1 and Phase 2 of `HIGHLIGHT_PERF_PLAN.md`. The ES|QL operator is
not architecturally different from default-mapping Query DSL; it is the same algorithm minus
two optimisations. Parity on default mappings therefore does not require Stage 2.

The real architectural gap is a different configuration: with `index_options: offsets` the
DSL path resolves to `POSTINGS` and reads offsets straight from the segment, doing no
analysis at all. That is what Stage 2 corresponds to, and it is a separate measurement.

### Options already match

Both sides default to 5 fragments, `fragment_size` 100, `<em>`/`</em>`, `no_match_size` 0,
encoder `default`, document order, `require_field_match` true, sentence boundary scanner
(`HighlightBuilder:49-101`, `HighlightOptions:47-62`). ES|QL mirrored the DSL defaults
deliberately, so no options need fudging to make the comparison fair.

### One asymmetry to expect in the results

`QueryMaxAnalyzedOffset.create` returns null for a null input, so Query DSL does **not** wrap
the analyzer unless the user sets `max_analyzed_offset`. `HighlightOperator` passes `-1`,
which resolves to the index maximum and produces a non-null offset
(`HighlightOperator:105-110`), so ES|QL always pays a `LimitTokenOffsetFilter` per token. It
is a small cost and it is free to give back. The benchmark should be able to see it.

## 3. Arms

Four engines, same corpus, same query, same options, same JVM and fork:

| Engine | Code under test | Offset source | Represents |
|---|---|---|---|
| `esql` | `HighlightOperator`, wired as `planHighlight` does | `POSTINGS` over a per-row `MemoryIndex` | ES\|QL today |
| `dslAnalysis` | `HighlightPhase` → `DefaultHighlighter`, default `text` | `ANALYSIS` → `MemoryIndexOffsetStrategy` | DSL as almost everyone runs it |
| `dslPostings` | same, `index_options: offsets` | `POSTINGS` from real postings | the Stage 2 ceiling |
| `dslTermVectors` | same, `term_vector: with_positions_offsets` | `TERM_VECTORS` | the other config used for large documents |

`esql` versus `dslAnalysis` is the apples-to-apples comparison: same algorithm, so the whole
gap is avoidable. `esql` versus `dslPostings` is the total gap. The difference between the two
is the part that needs Stage 2.

## 4. Measurement boundary

Everything inside the highlight step; nothing before it.

For Query DSL that means `FetchSubPhaseProcessor.process(hitContext)` with `hitContext.source()`
already decoded, which is exactly how `FetchPhase` invokes it. Field extraction from `_source`
via `ValueFetcher` *is* inside the measured region, because `DefaultHighlighter.highlight`
calls `loadFieldValues` itself.

For ES|QL it means `HighlightOperator.process(page)`, where `LoadFromPageEvaluator` is
likewise inside the operator.

The boundary is symmetric and matches where each system actually draws the line. `_source`
byte decoding (DSL) and `ValuesSourceReaderOperator` field loading (ES|QL) are both excluded.

## 5. Components

New package: `benchmarks/src/main/java/org/elasticsearch/benchmark/esql/highlight/`, holding
five classes.

### `HighlightEsqlVsQueryDslBenchmark`

The JMH class itself: parameters, `@Setup` (corpus, harness selection, self-test), the single
`@Benchmark` method dispatching to whichever harness the `engine` parameter selected, and
`@TearDown`. It owns no highlighting logic; it only wires and measures.

### `HighlightCorpus`

Deterministic document generation: `Random(42)`, 4-5 character lowercase tokens, sentences of
8-16 tokens with a capitalised first token so UAX#29 breaks correctly, and the query term
placed at random positions.

The `hit` arm queries `fox`, planted once in every document. The `miss` arm queries `cat`,
absent from every document. Both terms must target the *highlighted* field. If a miss query
targeted some other field instead, the query would contribute no terms for the highlighted
field, Lucene would resolve a `NoOpOffsetStrategy`, and `CustomUnifiedHighlighter:139-141`
would short-circuit the whole call to zero work — making DSL look infinitely fast on misses
and the comparison worthless.

The generator is copied from `HighlightAtRuntimeBenchmark:208-254` rather than extracted,
because `HIGHLIGHT_PERF_PLAN.md` freezes that file so its published baseline stays
comparable. Keeping the corpus character-identical lets the new harness be sanity-checked
against the known ES|QL numbers (4,657 ns/row at 16 tokens, 186,366 at 1024).

### `DslHighlightHarness`

The real DSL stack, built once per trial:

- `MapperService` from the existing `benchmarks/.../index/mapper/MapperServiceFactory:59-106`,
  with the mapping variant for the arm.
- A `ByteBuffersDirectory` index written through `mapperService.documentMapper().parse(...)`
  so `_source` is stored and, for the offsets and term-vector variants, real offsets exist.
  Force-merged to a single leaf: matches a warmed shard and keeps doc-id arithmetic trivial.
  Modelled on `QueryParserHelperBenchmark:87-113`.
- A `ContextIndexSearcher` (`ContextIndexSearcher:98-106` is public).
- A `SearchExecutionContext` modelled on `QueryParserHelperBenchmark:138-166`.
- `FieldOptions` from a genuine `new HighlightBuilder().field(FIELD).build(sec)`, so the
  options are the real DSL defaults rather than our reconstruction of them.
- A per-document `HitContext` (`FetchSubPhase.HitContext:37-51`) assembled the way
  `FetchPhase` does.

### `BenchmarkSearchContext`

`FetchContext` has one constructor and it takes a `SearchContext`, so making `FetchContext`
real requires a `SearchContext`. `SearchContext` has a protected no-arg constructor
(`SearchContext:92`) and 88 abstract methods.

Eight of them return real values: `getSearchExecutionContext`, `searcher`,
`parsedQuery`, `highlight`, `storedFieldsContext`, `fetchSourceContext`, `hasStoredFields`,
`hasScriptFields`. The last four are needed because the `FetchContext` constructor calls them.
Every other method throws `UnsupportedOperationException` with a message naming this
benchmark, so an unexpected call fails loudly instead of quietly returning null.

Paired with a three-line `FetchContext` subclass overriding `getIndexName()`, which otherwise
routes through `searchContext.indexShard().shardId()` (`FetchContext:97-99`) and would drag in
a real `IndexShard`. The index name is only used in highlighter error messages.

This is the price of running the actual DSL code instead of a reimplementation of it, and it
is worth paying: the numbers will be used to make a roadmap decision, and a hand-mirrored
`buildHighlighter` would leave every result open to "is that really what DSL does?".

Known cost: the benchmark stops compiling whenever `SearchContext` gains an abstract method.
That is a loud, trivially fixed failure, and preferable to a silent fidelity gap.

`test:framework` (which has `TestSearchContext`) is `testImplementation` only in
`benchmarks/build.gradle:117` and is not on the benchmark main classpath. Promoting it would
put randomizedtesting and mockito on the benchmark runtime classpath in a module whose
build file already fights jar hell, so we write the stub instead.

### `EsqlHighlightHarness`

The four calls `LocalExecutionPlanner.planHighlight:1352-1399` makes, in order:

1. `HighlightOptions.from(null, foldCtx)` for the defaults.
2. `HighlightQueryBuilders.translate(queryExpr, fieldNames, null, null)`.
3. `HighlightConfig(...).withExecutionContext(translated.analyzer(), translated.query(), fieldNames)`.
4. `new HighlightOperator.Factory(config, List.of(new LoadFromPageEvaluator.Factory(0)))`.

Using the real translator matters. `HighlightAtRuntimeBenchmark` hand-builds
`new StandardAnalyzer()` and `new TermQuery(...)`, but production parses the string as
`query_string` and returns `Lucene.STANDARD_ANALYZER`, a `NamedAnalyzer`
(`HighlightQueryBuilders:197-218`, `Lucene:104`). For `"fox"` the query does collapse to the
same `TermQuery`, but `HighlightOperator:109` branches on `instanceof NamedAnalyzer`, so the
analyzer object is not interchangeable.

Pages are a fixed 128 rows. Production sizes pages adaptively as
`max(32, 262144 / estimatedRowSize)` (`Operator:34-42`), which would vary with `textTokens`
and confound the comparison. `HighlightOperator` keeps no state across pages beyond a
per-page `HighlightField[]`, so a fixed size costs nothing in fidelity.

## 6. The self-test

`benchmarks/AGENTS.md` forbids skipping the self-test. Here it carries more weight than usual,
because every plausible way this benchmark can lie is silent. Three checks in `@Setup`:

**Assert the resolved `OffsetSource` for each DSL arm.** One wrong mapping key would make
`dslPostings` resolve to `ANALYSIS`, and the benchmark would then report a fabricated
architectural gap while looking perfectly healthy. Synthetic source would do the same to every
arm at once, via the `reordersFieldValues()` branch — with normal stored `_source` that returns
false (`SourceLoader:89-91`), which is why the corpus uses stored source.

`OffsetSource` alone does not fully pin the `dslAnalysis` arm, because `ANALYSIS` resolves to
`MemoryIndexOffsetStrategy` only while weight-matches is on, and `CustomUnifiedHighlighter:129-131`
strips that flag when `require_field_match` is false or the query shape is unsupported —
in which case Lucene picks `TokenStreamOffsetStrategy` instead and the arm quietly stops being
the comparison we wanted. The resolved strategy is unreachable (`fieldHighlighter` is private,
and reflecting into an exported package of a named module would need `--add-opens`), so the
self-test pins its three inputs instead: `IndexSettings.isWeightMatchesEnabled()` is true,
`require_field_match` is true, and the translated query is a plain `TermQuery`. Those three
together determine the strategy.

**Assert all four engines produce byte-identical snippets** for the same document and query.
This is what licenses the comparison. Single-valued text under 1M characters should agree
exactly. If it does not, that is a correctness finding to report verbatim, not something to
tolerate or work around. Two known divergences are documented and should not appear on
single-valued input: multi-value fragment alignment (`HighlightOperator:256-262`) and
truncation instead of an error past `max_analyzed_offset`.

## 7. Parameters and metrics

- `textTokens` = 16, 128, 1024, 8192 — same values as `HighlightAtRuntimeBenchmark` so its
  published baseline cross-checks the harness.
- `engine` = `esql`, `dslAnalysis`, `dslPostings`, `dslTermVectors`.
- `outcome` = `hit`, `miss`. The miss cell is the most interesting one: the plan measured an
  ES|QL miss at 62-87% of a hit, whereas DSL's keep-word filter should make a miss nearly
  free. If that shows up, it is the clearest single argument for Phase 2.
- `@OperationsPerInvocation(128)` so scores read directly as ns/document.

Allocation is measured with `-prof gc`, because allocation is where the existing profile found
the story: about 1.6 MB per row at 8192 tokens, roughly 87% of it postings growth inside
`MemoryIndex.storeTerms`.

## 8. Reporting

One table of engine × textTokens × outcome, with ns/document, bytes/document, and both
ratios (ES|QL to `dslAnalysis`, ES|QL to `dslPostings`). Plus an async-profiler run on the
`1024 × miss` cell using the recipe in `HIGHLIGHT_PERF_PLAN.md` section 5.

The writeup must state which part of the gap is avoidable and which needs Stage 2, since
that is the decision the numbers exist to inform.

## 9. Out of scope

- **Request latency.** `_search` with a highlight clause versus `_query` with `HIGHLIGHT` is a
  macrobenchmark. This repo has no in-process macro harness and delegates that work to Rally
  (`benchmarks/README.md`). The writeup must say so explicitly, so the JMH per-document number
  is not quoted as if it were request latency.
- **The row-count multiplier.** `PushLimitToSource:16-26` does not push a limit through
  `HighlightExec`, so the plan is `EsQuery → HighlightOperator → LimitOperator` and ES|QL
  highlights roughly `limit` rows (default 1000) per driver where DSL highlights about `size`
  hits (default 10). `LimitOperator:97-99` does stop the driver, so this is bounded, not
  unbounded. It is an ES|QL semantics difference rather than an inefficiency, it dominates the
  user-visible cost, and JMH should not try to simulate it. Mention it in the writeup.
- `semantic_text` highlighting, synthetic source, multi-field and multi-valued arms, and any
  edit to `HighlightAtRuntimeBenchmark`.

## 10. Verification

```bash
./gradlew :benchmarks:compileJava
./gradlew :benchmarks:spotlessJavaCheck
```

Then, from `benchmarks/`, per `benchmarks/AGENTS.md`:

```bash
cd benchmarks
../gradlew run --args "org.elasticsearch.benchmark.esql.highlight.HighlightEsqlVsQueryDslBenchmark \
  -f 3 -foe true -prof gc -rf json -rff build/highlight-vs-dsl.json" | tee /tmp/bench/highlight_vs_dsl
```

A first smoke run should use `-f 1 -wi 1 -i 1 -p textTokens=128` to confirm the self-test
passes on all four engines before committing to the full matrix, which will take well over an
hour at 32 cells.
