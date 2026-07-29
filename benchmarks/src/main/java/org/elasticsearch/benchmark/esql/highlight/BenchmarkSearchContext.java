/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.esql.highlight;

import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TotalHits;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.cache.bitset.BitsetFilterCache;
import org.elasticsearch.index.mapper.IdLoader;
import org.elasticsearch.index.mapper.SourceLoader;
import org.elasticsearch.index.query.ParsedQuery;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.search.SearchExtBuilder;
import org.elasticsearch.search.SearchShardTarget;
import org.elasticsearch.search.aggregations.SearchContextAggregations;
import org.elasticsearch.search.collapse.CollapseContext;
import org.elasticsearch.search.dfs.DfsSearchResult;
import org.elasticsearch.search.fetch.FetchPhase;
import org.elasticsearch.search.fetch.FetchSearchResult;
import org.elasticsearch.search.fetch.StoredFieldsContext;
import org.elasticsearch.search.fetch.subphase.FetchDocValuesContext;
import org.elasticsearch.search.fetch.subphase.FetchFieldsContext;
import org.elasticsearch.search.fetch.subphase.FetchSourceContext;
import org.elasticsearch.search.fetch.subphase.ScriptFieldsContext;
import org.elasticsearch.search.fetch.subphase.highlight.SearchHighlightContext;
import org.elasticsearch.search.internal.ContextIndexSearcher;
import org.elasticsearch.search.internal.ReaderContext;
import org.elasticsearch.search.internal.ScrollContext;
import org.elasticsearch.search.internal.SearchContext;
import org.elasticsearch.search.internal.ShardSearchContextId;
import org.elasticsearch.search.internal.ShardSearchRequest;
import org.elasticsearch.search.lookup.SourceFilter;
import org.elasticsearch.search.profile.Profilers;
import org.elasticsearch.search.query.QuerySearchResult;
import org.elasticsearch.search.rank.context.QueryPhaseRankShardContext;
import org.elasticsearch.search.rank.feature.RankFeatureResult;
import org.elasticsearch.search.rescore.RescoreContext;
import org.elasticsearch.search.sort.SortAndFormats;
import org.elasticsearch.search.suggest.SuggestionSearchContext;
import org.elasticsearch.tasks.CancellableTask;

import java.util.List;

/**
 * The minimum {@link SearchContext} needed to build a real {@link org.elasticsearch.search.fetch.FetchContext}, so the
 * benchmark can drive the production {@link org.elasticsearch.search.fetch.subphase.highlight.HighlightPhase} instead of
 * a reimplementation of it.
 * <p>
 * {@code FetchContext} has a single constructor and it takes a {@code SearchContext}, so there is no way to reach the
 * real highlight subphase without one. Only six values are genuinely needed
 * ({@link #getSearchExecutionContext()}, {@link #searcher()}, {@link #parsedQuery()}, {@link #highlight()}, and the
 * {@link #hasStoredFields()}/{@link #fetchSourceContext()} pair that {@code FetchContext}'s constructor reads).
 * <p>
 * The remaining methods split two ways on purpose. Plain getters answer "nothing configured" — null, false, or the
 * documented default — because {@code FetchContext} calls several of them while assembling itself and an exception there
 * would be noise rather than signal. Anything that implies a search phase is actually executing (result objects,
 * mutators, the shard, the circuit breaker) throws instead, so a wiring mistake surfaces immediately rather than being
 * absorbed as a silent null.
 * <p>
 * This class stops compiling whenever {@code SearchContext} gains an abstract method. That is deliberate: a loud
 * compile failure is the correct outcome, since the new method may well be one the highlight path needs.
 */
public final class BenchmarkSearchContext extends SearchContext {

    private final SearchExecutionContext searchExecutionContext;
    private final ContextIndexSearcher searcher;
    private final ParsedQuery parsedQuery;
    private final SearchHighlightContext highlight;

    public BenchmarkSearchContext(
        SearchExecutionContext searchExecutionContext,
        ContextIndexSearcher searcher,
        ParsedQuery parsedQuery,
        SearchHighlightContext highlight
    ) {
        this.searchExecutionContext = searchExecutionContext;
        this.searcher = searcher;
        this.parsedQuery = parsedQuery;
        this.highlight = highlight;
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("not needed by the HIGHLIGHT vs Query DSL benchmark");
    }

    // -- the values the highlight path actually reads ------------------------------------------------------------

    @Override
    public SearchExecutionContext getSearchExecutionContext() {
        return searchExecutionContext;
    }

    @Override
    public ContextIndexSearcher searcher() {
        return searcher;
    }

    @Override
    public ParsedQuery parsedQuery() {
        return parsedQuery;
    }

    @Override
    public Query query() {
        return parsedQuery.query();
    }

    @Override
    public SearchHighlightContext highlight() {
        return highlight;
    }

    @Override
    public SourceLoader newSourceLoader(@Nullable SourceFilter sourceFilter) {
        return SourceLoader.FROM_STORED_SOURCE;
    }

    // -- "nothing configured" getters, several of which FetchContext's constructor reads -------------------------

    @Override
    public boolean hasStoredFields() {
        return false;
    }

    @Override
    public boolean hasScriptFields() {
        return false;
    }

    @Override
    public StoredFieldsContext storedFieldsContext() {
        return null;
    }

    @Override
    public FetchSourceContext fetchSourceContext() {
        return null;
    }

    @Override
    public boolean sourceRequested() {
        return true;
    }

    @Override
    public ScriptFieldsContext scriptFields() {
        return null;
    }

    @Override
    public FetchDocValuesContext docValuesContext() {
        return null;
    }

    @Override
    public FetchFieldsContext fetchFieldsContext() {
        return null;
    }

    @Override
    public ParsedQuery parsedPostFilter() {
        return null;
    }

    @Override
    public CollapseContext collapse() {
        return null;
    }

    @Override
    public List<RescoreContext> rescore() {
        return List.of();
    }

    @Override
    public boolean explain() {
        return false;
    }

    @Override
    public boolean version() {
        return false;
    }

    @Override
    public boolean seqNoAndPrimaryTerm() {
        return false;
    }

    @Override
    public SortAndFormats sort() {
        return null;
    }

    @Override
    public boolean trackScores() {
        return false;
    }

    @Override
    public int trackTotalHitsUpTo() {
        return DEFAULT_TRACK_TOTAL_HITS_UP_TO;
    }

    @Override
    public FieldDoc searchAfter() {
        return null;
    }

    @Override
    public Float minimumScore() {
        return null;
    }

    @Override
    public int from() {
        return 0;
    }

    @Override
    public int size() {
        return 10;
    }

    @Override
    public int terminateAfter() {
        return DEFAULT_TERMINATE_AFTER;
    }

    @Override
    public TimeValue timeout() {
        return TimeValue.MINUS_ONE;
    }

    @Override
    public boolean lowLevelCancellation() {
        return false;
    }

    @Override
    public boolean isCancelled() {
        return false;
    }

    @Override
    public CancellableTask getTask() {
        return null;
    }

    @Override
    public void setTask(CancellableTask task) {}

    @Override
    public List<String> groupStats() {
        return null;
    }

    @Override
    public int numberOfShards() {
        return 1;
    }

    @Override
    public SearchType searchType() {
        return SearchType.QUERY_THEN_FETCH;
    }

    @Override
    public ScrollContext scrollContext() {
        return null;
    }

    @Override
    public SuggestionSearchContext suggest() {
        return null;
    }

    @Override
    public SearchContextAggregations aggregations() {
        return null;
    }

    @Override
    public QueryPhaseRankShardContext queryPhaseRankShardContext() {
        return null;
    }

    @Override
    public SearchExtBuilder getSearchExt(String name) {
        return null;
    }

    @Override
    public Profilers getProfilers() {
        return null;
    }

    @Override
    public ShardSearchRequest request() {
        return null;
    }

    @Override
    public ShardSearchContextId id() {
        return null;
    }

    @Override
    public SearchShardTarget shardTarget() {
        return null;
    }

    @Override
    public String source() {
        return null;
    }

    @Override
    public long getRelativeTimeInMillis() {
        return 0L;
    }

    // -- anything below implies a search phase is running, which it is not --------------------------------------

    @Override
    public void preProcess() {
        throw unsupported();
    }

    @Override
    public Query buildFilteredQuery(Query query) {
        throw unsupported();
    }

    @Override
    public IndexShard indexShard() {
        throw unsupported();
    }

    @Override
    public BitsetFilterCache bitsetFilterCache() {
        throw unsupported();
    }

    @Override
    public ReaderContext readerContext() {
        throw unsupported();
    }

    @Override
    public IdLoader newIdLoader() {
        throw unsupported();
    }

    @Override
    public CircuitBreaker circuitBreaker() {
        throw unsupported();
    }

    @Override
    public long memAccountingBufferSize() {
        throw unsupported();
    }

    @Override
    public TotalHits getTotalHits() {
        throw unsupported();
    }

    @Override
    public float getMaxScore() {
        throw unsupported();
    }

    @Override
    public FetchPhase fetchPhase() {
        throw unsupported();
    }

    @Override
    public DfsSearchResult dfsResult() {
        throw unsupported();
    }

    @Override
    public void addDfsResult() {
        throw unsupported();
    }

    @Override
    public QuerySearchResult queryResult() {
        throw unsupported();
    }

    @Override
    public void addQueryResult() {
        throw unsupported();
    }

    @Override
    public RankFeatureResult rankFeatureResult() {
        throw unsupported();
    }

    @Override
    public void addRankFeatureResult() {
        throw unsupported();
    }

    @Override
    public FetchSearchResult fetchResult() {
        throw unsupported();
    }

    @Override
    public void addFetchResult() {
        throw unsupported();
    }

    @Override
    public SearchContext aggregations(SearchContextAggregations aggregations) {
        throw unsupported();
    }

    @Override
    public void highlight(SearchHighlightContext highlight) {
        throw unsupported();
    }

    @Override
    public void queryPhaseRankShardContext(QueryPhaseRankShardContext queryPhaseRankShardContext) {
        throw unsupported();
    }

    @Override
    public void addRescore(RescoreContext rescore) {
        throw unsupported();
    }

    @Override
    public SearchContext fetchSourceContext(FetchSourceContext fetchSourceContext) {
        throw unsupported();
    }

    @Override
    public SearchContext docValuesContext(FetchDocValuesContext docValuesContext) {
        throw unsupported();
    }

    @Override
    public SearchContext fetchFieldsContext(FetchFieldsContext fetchFieldsContext) {
        throw unsupported();
    }

    @Override
    public void terminateAfter(int terminateAfter) {
        throw unsupported();
    }

    @Override
    public SearchContext minimumScore(float minimumScore) {
        throw unsupported();
    }

    @Override
    public SearchContext sort(SortAndFormats sort) {
        throw unsupported();
    }

    @Override
    public SearchContext trackScores(boolean trackScores) {
        throw unsupported();
    }

    @Override
    public SearchContext trackTotalHitsUpTo(int trackTotalHits) {
        throw unsupported();
    }

    @Override
    public SearchContext searchAfter(FieldDoc searchAfter) {
        throw unsupported();
    }

    @Override
    public SearchContext parsedPostFilter(ParsedQuery postFilter) {
        throw unsupported();
    }

    @Override
    public SearchContext parsedQuery(ParsedQuery query) {
        throw unsupported();
    }

    @Override
    public SearchContext from(int from) {
        throw unsupported();
    }

    @Override
    public SearchContext size(int size) {
        throw unsupported();
    }

    @Override
    public SearchContext storedFieldsContext(StoredFieldsContext storedFieldsContext) {
        throw unsupported();
    }

    @Override
    public void explain(boolean explain) {
        throw unsupported();
    }

    @Override
    public void version(boolean version) {
        throw unsupported();
    }

    @Override
    public void seqNoAndPrimaryTerm(boolean seqNoAndPrimaryTerm) {
        throw unsupported();
    }
}
