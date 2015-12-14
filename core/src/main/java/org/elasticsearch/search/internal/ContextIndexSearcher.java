/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.internal;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermContext;
import org.apache.lucene.search.*;
import org.elasticsearch.common.lease.Releasable;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.search.dfs.AggregatedDfs;
import org.elasticsearch.search.profile.ProfileBreakdown;
import org.elasticsearch.search.profile.ProfileWeight;
import org.elasticsearch.search.profile.Profiler;

import java.io.IOException;

/**
 * Context-aware extension of {@link IndexSearcher}.
 */
public class ContextIndexSearcher extends IndexSearcher implements Releasable {

    /** The wrapped {@link IndexSearcher}. The reason why we sometimes prefer delegating to this searcher instead of <tt>super</tt> is that
     *  this instance may have more assertions, for example if it comes from MockInternalEngine which wraps the IndexSearcher into an
     *  AssertingIndexSearcher. */
    private final IndexSearcher in;

    private AggregatedDfs aggregatedDfs;

    private final Engine.Searcher engineSearcher;

    // TODO revisit moving the profiler to inheritance or wrapping model in the future
    private Profiler profiler;

    public ContextIndexSearcher(Engine.Searcher searcher,
            QueryCache queryCache, QueryCachingPolicy queryCachingPolicy) {
        super(searcher.reader());
        in = searcher.searcher();
        engineSearcher = searcher;
        setSimilarity(searcher.searcher().getSimilarity(true));
        setQueryCache(queryCache);
        setQueryCachingPolicy(queryCachingPolicy);
    }

    @Override
    public void close() {
    }

    public void setProfiler(Profiler profiler) {
        this.profiler = profiler;
    }

    public void setAggregatedDfs(AggregatedDfs aggregatedDfs) {
        this.aggregatedDfs = aggregatedDfs;
    }

    @Override
    public Query rewrite(Query original) throws IOException {
        if (profiler != null) {
            profiler.startRewriteTime();
        }

        try {
            return in.rewrite(original);
        } finally {
            if (profiler != null) {
                profiler.stopAndAddRewriteTime();
            }
        }
    }

    @Override
    public Weight createNormalizedWeight(Query query, boolean needsScores) throws IOException {
        // During tests we prefer to use the wrapped IndexSearcher, because then we use the AssertingIndexSearcher
        // it is hacky, because if we perform a dfs search, we don't use the wrapped IndexSearcher...
        if (aggregatedDfs != null && needsScores) {
            // if scores are needed and we have dfs data then use it
            return super.createNormalizedWeight(query, needsScores);
        } else if (profiler != null) {
            // we need to use the createWeight method to insert the wrappers
            return super.createNormalizedWeight(query, needsScores);
        } else {
            return in.createNormalizedWeight(query, needsScores);
        }
    }

    @Override
    public Weight createWeight(Query query, boolean needsScores) throws IOException {
        if (profiler != null) {
            // createWeight() is called for each query in the tree, so we tell the queryProfiler
            // each invocation so that it can build an internal representation of the query
            // tree
            ProfileBreakdown profile = profiler.getQueryBreakdown(query);
            profile.startTime(ProfileBreakdown.TimingType.CREATE_WEIGHT);
            final Weight weight;
            try {
                weight = super.createWeight(query, needsScores);
            } finally {
                profile.stopAndRecordTime();
                profiler.pollLastQuery();
            }
            return new ProfileWeight(query, weight, profile);
        } else {
            // needs to be 'super', not 'in' in order to use aggregated DFS
            return super.createWeight(query, needsScores);
        }
    }

    @Override
    public Explanation explain(Query query, int doc) throws IOException {
        return in.explain(query, doc);
    }

    @Override
    public TermStatistics termStatistics(Term term, TermContext context) throws IOException {
        if (aggregatedDfs == null) {
            // we are either executing the dfs phase or the search_type doesn't include the dfs phase.
            return super.termStatistics(term, context);
        }
        TermStatistics termStatistics = aggregatedDfs.termStatistics().get(term);
        if (termStatistics == null) {
            // we don't have stats for this - this might be a must_not clauses etc. that doesn't allow extract terms on the query
            return super.termStatistics(term, context);
        }
        return termStatistics;
    }

    @Override
    public CollectionStatistics collectionStatistics(String field) throws IOException {
        if (aggregatedDfs == null) {
            // we are either executing the dfs phase or the search_type doesn't include the dfs phase.
            return super.collectionStatistics(field);
        }
        CollectionStatistics collectionStatistics = aggregatedDfs.fieldStatistics().get(field);
        if (collectionStatistics == null) {
            // we don't have stats for this - this might be a must_not clauses etc. that doesn't allow extract terms on the query
            return super.collectionStatistics(field);
        }
        return collectionStatistics;
    }

    public DirectoryReader getDirectoryReader() {
        return engineSearcher.getDirectoryReader();
    }
}
