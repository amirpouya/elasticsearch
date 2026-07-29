/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.esql.highlight;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter.OffsetSource;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.elasticsearch.benchmark.index.mapper.MapperServiceFactory;
import org.elasticsearch.cluster.ClusterModule;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.LoggingDeprecationHandler;
import org.elasticsearch.core.IOUtils;
import org.elasticsearch.index.cache.query.TrivialQueryCachingPolicy;
import org.elasticsearch.index.fielddata.IndexFieldDataCache;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.MapperMetrics;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.ParsedDocument;
import org.elasticsearch.index.mapper.SourceLoader;
import org.elasticsearch.index.mapper.SourceToParse;
import org.elasticsearch.index.query.ParsedQuery;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.index.search.stats.SearchStatsSettings;
import org.elasticsearch.index.search.stats.ShardSearchStats;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.similarity.SimilarityService;
import org.elasticsearch.indices.breaker.NoneCircuitBreakerService;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.fetch.FetchContext;
import org.elasticsearch.search.fetch.FetchSubPhase.HitContext;
import org.elasticsearch.search.fetch.FetchSubPhaseProcessor;
import org.elasticsearch.search.fetch.subphase.highlight.DefaultHighlighter;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightField;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightPhase;
import org.elasticsearch.search.fetch.subphase.highlight.SearchHighlightContext;
import org.elasticsearch.search.internal.ContextIndexSearcher;
import org.elasticsearch.search.internal.SearchContext;
import org.elasticsearch.search.lookup.Source;
import org.elasticsearch.xcontent.NamedXContentRegistry;
import org.elasticsearch.xcontent.Text;
import org.elasticsearch.xcontent.XContentParserConfiguration;
import org.elasticsearch.xcontent.XContentType;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Runs the production Query DSL {@code unified} highlighter over a real Lucene index.
 * <p>
 * The measured call is {@link FetchSubPhaseProcessor#process}, which is what the fetch phase invokes per hit. Nothing
 * about {@link DefaultHighlighter} is reimplemented: the harness only assembles the inputs the fetch phase would have
 * assembled, so the offset-source choice, break iterator, passage formatter, per-request highlighter caching and
 * {@code _source} field extraction are all production code.
 * <p>
 * {@link HitContext}s are built during construction rather than per invocation, because decoding {@code _source} bytes
 * and allocating the {@link SearchHit} happen in the fetch phase <em>before</em> the highlight subphase runs. Only work
 * the highlighter itself does belongs inside the measured region.
 */
public final class DslHighlightHarness implements Closeable {

    static final String FIELD = "content";

    /**
     * The mapping variants that select each {@link OffsetSource}, per {@code DefaultHighlighter#getOffsetSource}. These
     * are the point of the DSL side of the comparison: {@link #ANALYSIS} re-analyses text per document the way ES|QL
     * does, while {@link #POSTINGS} reads offsets from the segment and does no analysis at all.
     */
    public enum Variant {
        /** A default {@code text} field: positions but no offsets, so the highlighter must re-analyse. */
        ANALYSIS("""
            {"_doc":{"properties":{"content":{"type":"text"}}}}""", OffsetSource.ANALYSIS),

        /** Offsets in the postings list, so the highlighter reads them straight from the segment. */
        POSTINGS("""
            {"_doc":{"properties":{"content":{"type":"text","index_options":"offsets"}}}}""", OffsetSource.POSTINGS),

        /** Offsets in term vectors instead of the postings list. */
        TERM_VECTORS("""
            {"_doc":{"properties":{"content":{"type":"text","term_vector":"with_positions_offsets"}}}}""", OffsetSource.TERM_VECTORS);

        private final String mappings;
        private final OffsetSource expectedOffsetSource;

        Variant(String mappings, OffsetSource expectedOffsetSource) {
            this.mappings = mappings;
            this.expectedOffsetSource = expectedOffsetSource;
        }

        public OffsetSource expectedOffsetSource() {
            return expectedOffsetSource;
        }
    }

    /**
     * Exposes {@code DefaultHighlighter}'s {@code protected static} offset-source decision so the self-test can assert
     * each arm resolved the source it claims to measure. A mapping typo would otherwise silently collapse
     * {@link Variant#POSTINGS} to {@link OffsetSource#ANALYSIS}, and the benchmark would report a fabricated
     * architectural gap while looking healthy.
     */
    private static final class OffsetSourceProbe extends DefaultHighlighter {
        static OffsetSource resolve(FetchContext context, MappedFieldType fieldType) {
            return getOffsetSource(context, fieldType);
        }
    }

    /**
     * Supplies the index name without an {@link IndexShard}. {@link FetchContext#getIndexName()} otherwise reaches it
     * through {@code searchContext.indexShard().shardId()}, and the name is only used in highlighter error messages.
     */
    private static final class BenchmarkFetchContext extends FetchContext {
        BenchmarkFetchContext(SearchContext searchContext) {
            super(searchContext, SourceLoader.FROM_STORED_SOURCE);
        }

        @Override
        public String getIndexName() {
            return "index";
        }
    }

    private final Variant variant;
    private final MapperService mapperService;
    private final Directory directory;
    private final DirectoryReader reader;
    private final ContextIndexSearcher searcher;
    private final SearchExecutionContext searchExecutionContext;
    private final SearchHighlightContext highlightContext;
    private final FetchContext fetchContext;
    private final FetchSubPhaseProcessor processor;
    private final List<HitContext> hits;
    private final Query query;

    public DslHighlightHarness(Variant variant, List<String> documents, String queryTerm) throws IOException {
        this.variant = variant;
        this.mapperService = MapperServiceFactory.create(variant.mappings);

        List<BytesReference> sources = new ArrayList<>(documents.size());
        this.directory = new ByteBuffersDirectory();
        IndexWriterConfig config = new IndexWriterConfig(IndexShard.buildIndexAnalyzer(mapperService));
        try (IndexWriter writer = new IndexWriter(directory, config)) {
            for (int i = 0; i < documents.size(); i++) {
                BytesReference source = sourceFor(documents.get(i));
                sources.add(source);
                ParsedDocument parsed = mapperService.documentMapper()
                    .parse(new SourceToParse(Integer.toString(i), source, XContentType.JSON));
                writer.addDocument(parsed.rootDoc());
            }
            // A single segment, so doc ids are insertion order and the reader has one leaf. This is also what a
            // warmed, merged shard looks like in production.
            writer.forceMerge(1);
        }
        this.reader = DirectoryReader.open(directory);
        this.searcher = new ContextIndexSearcher(
            reader,
            IndexSearcher.getDefaultSimilarity(),
            // No query cache: caching across invocations would leave later iterations measuring something else.
            null,
            TrivialQueryCachingPolicy.NEVER,
            false
        );
        this.searchExecutionContext = buildSearchExecutionContext();

        // The Query DSL equivalent of HIGHLIGHT "<term>" ON content. ES|QL translates its literal through
        // QueryStringQueryBuilder over the ON fields, so this is the same builder; the self-test asserts both arms end
        // up with the same Lucene query, which keeps the comparison down to one variable.
        this.query = searchExecutionContext.toQuery(QueryBuilders.queryStringQuery(queryTerm).field(FIELD)).query();
        this.highlightContext = new HighlightBuilder().field(FIELD).build(searchExecutionContext);

        SearchContext searchContext = new BenchmarkSearchContext(
            searchExecutionContext,
            searcher,
            new ParsedQuery(query),
            highlightContext
        );
        this.fetchContext = new BenchmarkFetchContext(searchContext);
        this.processor = new HighlightPhase(Map.of(DefaultHighlighter.NAME, new DefaultHighlighter())).getProcessor(fetchContext);

        LeafReaderContext leaf = reader.leaves().getFirst();
        processor.setNextReader(leaf);
        this.hits = new ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
            hits.add(new HitContext(SearchHit.unpooled(i), leaf, i, Map.of(), Source.fromBytes(sources.get(i)), null));
        }
    }

    // The corpus is lowercase ASCII letters, spaces and periods, so no JSON escaping is needed.
    private static BytesReference sourceFor(String text) {
        return new BytesArray("{\"" + FIELD + "\":\"" + text + "\"}");
    }

    /** Highlights every document the way the fetch phase would. Returns how many produced a snippet. */
    public int highlight() throws IOException {
        int highlighted = 0;
        for (HitContext hit : hits) {
            processor.process(hit);
            if (hit.hit().getHighlightFields().isEmpty() == false) {
                highlighted++;
            }
        }
        return highlighted;
    }

    /** Snippets for one document, for the cross-engine equality check. Null when the document did not match. */
    public List<String> snippets(int doc) throws IOException {
        HitContext hit = hits.get(doc);
        processor.process(hit);
        HighlightField field = hit.hit().getHighlightFields().get(FIELD);
        if (field == null || field.fragments() == null) {
            return null;
        }
        return Arrays.stream(field.fragments()).map(Text::string).toList();
    }

    /** The {@link OffsetSource} this arm actually resolved. */
    public OffsetSource resolvedOffsetSource() {
        return OffsetSourceProbe.resolve(fetchContext, searchExecutionContext.getFieldType(FIELD));
    }

    /**
     * Whether weight-matches is on. Together with {@code require_field_match} and a plain term query, this pins
     * {@link OffsetSource#ANALYSIS} to Lucene's {@code MemoryIndexOffsetStrategy} rather than
     * {@code TokenStreamOffsetStrategy} — and it is the memory-index strategy that makes the ANALYSIS arm the
     * apples-to-apples comparison against ES|QL.
     */
    public boolean weightMatchesEnabled() {
        return searchExecutionContext.getIndexSettings().isWeightMatchesEnabled();
    }

    /** The other input that keeps weight-matches on, and so keeps the ANALYSIS arm on the memory-index strategy. */
    public boolean requireFieldMatch() {
        return highlightContext.fields().iterator().next().fieldOptions().requireFieldMatch();
    }

    public Query query() {
        return query;
    }

    public Variant variant() {
        return variant;
    }

    private SearchExecutionContext buildSearchExecutionContext() {
        SimilarityService similarityService = new SimilarityService(mapperService.getIndexSettings(), null, Map.of());
        return new SearchExecutionContext(
            0,
            0,
            mapperService.getIndexSettings(),
            null,
            (ft, fdc) -> ft.fielddataBuilder(fdc).build(new IndexFieldDataCache.None(), new NoneCircuitBreakerService()),
            mapperService,
            mapperService.mappingLookup(),
            similarityService,
            null,
            XContentParserConfiguration.EMPTY.withRegistry(new NamedXContentRegistry(ClusterModule.getNamedXWriteables()))
                .withDeprecationHandler(LoggingDeprecationHandler.INSTANCE),
            new NamedWriteableRegistry(ClusterModule.getNamedWriteables()),
            null,
            searcher,
            () -> 1L,
            null,
            null,
            () -> true,
            null,
            Collections.emptyMap(),
            null,
            MapperMetrics.NOOP,
            new ShardSearchStats(new SearchStatsSettings(new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS)))
        );
    }

    @Override
    public void close() {
        IOUtils.closeWhileHandlingException(reader, directory);
    }
}
