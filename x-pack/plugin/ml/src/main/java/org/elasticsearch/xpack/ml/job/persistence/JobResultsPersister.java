/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.ml.job.persistence;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.DocWriteResponse.Result;
import org.elasticsearch.action.admin.indices.refresh.RefreshRequest;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.client.internal.OriginSettingClient;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.IdsQueryBuilder;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.xcontent.ToXContent;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xpack.core.ml.annotations.AnnotationIndex;
import org.elasticsearch.xpack.core.ml.datafeed.DatafeedTimingStats;
import org.elasticsearch.xpack.core.ml.job.persistence.AnomalyDetectorsIndex;
import org.elasticsearch.xpack.core.ml.job.persistence.ElasticsearchMappings;
import org.elasticsearch.xpack.core.ml.job.process.autodetect.state.CategorizerStats;
import org.elasticsearch.xpack.core.ml.job.process.autodetect.state.ModelSizeStats;
import org.elasticsearch.xpack.core.ml.job.process.autodetect.state.ModelSnapshot;
import org.elasticsearch.xpack.core.ml.job.process.autodetect.state.Quantiles;
import org.elasticsearch.xpack.core.ml.job.process.autodetect.state.TimingStats;
import org.elasticsearch.xpack.core.ml.job.results.AnomalyRecord;
import org.elasticsearch.xpack.core.ml.job.results.Bucket;
import org.elasticsearch.xpack.core.ml.job.results.BucketInfluencer;
import org.elasticsearch.xpack.core.ml.job.results.CategoryDefinition;
import org.elasticsearch.xpack.core.ml.job.results.Forecast;
import org.elasticsearch.xpack.core.ml.job.results.ForecastRequestStats;
import org.elasticsearch.xpack.core.ml.job.results.Influencer;
import org.elasticsearch.xpack.core.ml.job.results.ModelPlot;
import org.elasticsearch.xpack.core.ml.utils.ToXContentParams;
import org.elasticsearch.xpack.ml.utils.persistence.ResultsPersisterService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import static org.elasticsearch.core.Strings.format;
import static org.elasticsearch.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.xpack.core.ClientHelper.ML_ORIGIN;
import static org.elasticsearch.xpack.core.ClientHelper.executeAsyncWithOrigin;

/**
 * Persists result types, Quantiles etc to Elasticsearch<br>
 * <h2>Bucket</h2> Bucket result. The anomaly score of the bucket may not match the summed
 * score of all the records as all the records may not have been outputted for the
 * bucket. Contains bucket influencers that are persisted both with the bucket
 * and separately.
 * <b>Anomaly Record</b> Each record was generated by a detector which can be identified via
 * the detectorIndex field.
 * <b>Influencers</b>
 * <b>Quantiles</b> may contain model quantiles used in normalization and are
 * stored in documents of type {@link Quantiles#TYPE} <br>
 * <b>ModelSizeStats</b> This is stored in a flat structure <br>
 * <b>ModelSnapShot</b> This is stored in a flat structure <br>
 *
 * @see ElasticsearchMappings
 */
public class JobResultsPersister {

    private static final Logger logger = LogManager.getLogger(JobResultsPersister.class);

    private final OriginSettingClient client;
    private final ResultsPersisterService resultsPersisterService;

    /**
     * The possible types of data that may be committed.
     */
    public enum CommitType {
        RESULTS,
        STATE,
        ANNOTATIONS
    };

    public JobResultsPersister(OriginSettingClient client, ResultsPersisterService resultsPersisterService) {
        this.client = client;
        this.resultsPersisterService = resultsPersisterService;
    }

    public Builder bulkPersisterBuilder(String jobId) {
        return new Builder(jobId, () -> true);
    }

    public Builder bulkPersisterBuilder(String jobId, Supplier<Boolean> shouldRetry) {
        return new Builder(jobId, shouldRetry);
    }

    public class Builder {
        private final Map<String, IndexRequest> items;
        private final String jobId;
        private final String indexName;
        private final Supplier<Boolean> shouldRetry;

        private Builder(String jobId, Supplier<Boolean> shouldRetry) {
            this.items = new LinkedHashMap<>();
            this.jobId = Objects.requireNonNull(jobId);
            this.indexName = AnomalyDetectorsIndex.resultsWriteAlias(jobId);
            this.shouldRetry = shouldRetry;
        }

        /**
         * Persist the result bucket and its bucket influencers
         * Buckets are persisted with a consistent ID
         *
         * @param bucket The bucket to persist
         * @return this
         */
        public synchronized Builder persistBucket(Bucket bucket) {
            // If the supplied bucket has records then create a copy with records
            // removed, because we never persist nested records in buckets
            Bucket bucketWithoutRecords = bucket;
            if (bucketWithoutRecords.getRecords().isEmpty() == false) {
                bucketWithoutRecords = new Bucket(bucket);
                bucketWithoutRecords.setRecords(Collections.emptyList());
            }
            String id = bucketWithoutRecords.getId();
            logger.trace("[{}] ES API CALL: index bucket to index [{}] with ID [{}]", jobId, indexName, id);
            indexResult(id, bucketWithoutRecords, "bucket");

            persistBucketInfluencersStandalone(jobId, bucketWithoutRecords.getBucketInfluencers());

            return this;
        }

        private synchronized void persistBucketInfluencersStandalone(
            @SuppressWarnings("HiddenField") String jobId,
            List<BucketInfluencer> bucketInfluencers
        ) {
            if (bucketInfluencers != null && bucketInfluencers.isEmpty() == false) {
                for (BucketInfluencer bucketInfluencer : bucketInfluencers) {
                    String id = bucketInfluencer.getId();
                    logger.trace("[{}] ES BULK ACTION: index bucket influencer to index [{}] with ID [{}]", jobId, indexName, id);
                    indexResult(id, bucketInfluencer, "bucket influencer");
                }
            }
        }

        /**
         * Persist timing stats
         *
         * @param timingStats timing stats to persist
         * @return this
         */
        public synchronized Builder persistTimingStats(TimingStats timingStats) {
            indexResult(
                TimingStats.documentId(timingStats.getJobId()),
                timingStats,
                new ToXContent.MapParams(Collections.singletonMap(ToXContentParams.FOR_INTERNAL_STORAGE, "true")),
                TimingStats.TYPE.getPreferredName()
            );
            return this;
        }

        /**
         * Persist a list of anomaly records
         *
         * @param records the records to persist
         * @return this
         */
        public synchronized Builder persistRecords(List<AnomalyRecord> records) {
            for (AnomalyRecord record : records) {
                logger.trace("[{}] ES BULK ACTION: index record to index [{}] with ID [{}]", jobId, indexName, record.getId());
                indexResult(record.getId(), record, "record");
            }

            return this;
        }

        /**
         * Persist a list of influencers optionally using each influencer's ID or
         * an auto generated ID
         *
         * @param influencers the influencers to persist
         * @return this
         */
        public synchronized Builder persistInfluencers(List<Influencer> influencers) {
            for (Influencer influencer : influencers) {
                logger.trace("[{}] ES BULK ACTION: index influencer to index [{}] with ID [{}]", jobId, indexName, influencer.getId());
                indexResult(influencer.getId(), influencer, "influencer");
            }

            return this;
        }

        public synchronized Builder persistModelPlot(ModelPlot modelPlot) {
            logger.trace("[{}] ES BULK ACTION: index model plot to index [{}] with ID [{}]", jobId, indexName, modelPlot.getId());
            indexResult(modelPlot.getId(), modelPlot, "model plot");
            return this;
        }

        public synchronized Builder persistCategorizerStats(CategorizerStats categorizerStats) {
            logger.trace(
                "[{}] ES BULK ACTION: index categorizer stats to index [{}] with ID [{}]",
                jobId,
                indexName,
                categorizerStats.getId()
            );
            indexResult(categorizerStats.getId(), categorizerStats, "categorizer stats");
            return this;
        }

        public synchronized Builder persistCategoryDefinition(CategoryDefinition categoryDefinition) {
            logger.trace(
                "[{}] ES BULK ACTION: index category definition to index [{}] with ID [{}]",
                jobId,
                indexName,
                categoryDefinition.getId()
            );
            indexResult(categoryDefinition.getId(), categoryDefinition, "category definition");
            return this;
        }

        public synchronized Builder persistModelSizeStats(ModelSizeStats modelSizeStats) {
            logger.trace(
                "[{}] ES BULK ACTION: index model size stats to index [{}] with ID [{}]",
                jobId,
                indexName,
                modelSizeStats.getId()
            );
            indexResult(modelSizeStats.getId(), modelSizeStats, "model size stats");
            return this;
        }

        public synchronized Builder persistForecast(Forecast forecast) {
            logger.trace("[{}] ES BULK ACTION: index forecast to index [{}] with ID [{}]", jobId, indexName, forecast.getId());
            indexResult(forecast.getId(), forecast, Forecast.RESULT_TYPE_VALUE);
            return this;
        }

        public synchronized Builder persistForecastRequestStats(ForecastRequestStats forecastRequestStats) {
            logger.trace(
                "[{}] ES BULK ACTION: index forecast request stats to index [{}] with ID [{}]",
                jobId,
                indexName,
                forecastRequestStats.getId()
            );
            indexResult(forecastRequestStats.getId(), forecastRequestStats, "forecast request stats");
            return this;
        }

        private void indexResult(String id, ToXContent resultDoc, String resultType) {
            indexResult(id, resultDoc, ToXContent.EMPTY_PARAMS, resultType);
        }

        private void indexResult(String id, ToXContent resultDoc, ToXContent.Params params, String resultType) {
            try (XContentBuilder content = toXContentBuilder(resultDoc, params)) {
                items.put(id, new IndexRequest(indexName).id(id).source(content));
            } catch (IOException e) {
                logger.error(() -> format("[%s] Error serialising %s", jobId, resultType), e);
            }

            if (items.size() >= JobRenormalizedResultsPersister.BULK_LIMIT) {
                executeRequest();
            }
        }

        /**
         * Execute the bulk action
         */
        public synchronized void executeRequest() {
            if (items.isEmpty()) {
                return;
            }
            logger.trace("[{}] ES API CALL: bulk request with {} actions", jobId, items.size());
            resultsPersisterService.bulkIndexWithRetry(
                buildBulkRequest(),
                jobId,
                shouldRetry,
                retryMessage -> logger.debug("[{}] Bulk indexing of results failed {}", jobId, retryMessage)
            );
            clear();
        }

        private BulkRequest buildBulkRequest() {
            BulkRequest bulkRequest = new BulkRequest();
            for (IndexRequest item : items.values()) {
                bulkRequest.add(item);
            }
            return bulkRequest;
        }

        public synchronized void clear() {
            items.clear();
        }

        // for testing
        synchronized BulkRequest getBulkRequest() {
            return buildBulkRequest();
        }
    }

    /**
     * Persist the quantiles (blocking)
     */
    public void persistQuantiles(Quantiles quantiles, Supplier<Boolean> shouldRetry) {
        String jobId = quantiles.getJobId();
        String quantilesDocId = Quantiles.documentId(jobId);
        SearchRequest searchRequest = buildQuantilesDocIdSearch(quantilesDocId);
        SearchResponse searchResponse = resultsPersisterService.searchWithRetry(
            searchRequest,
            jobId,
            shouldRetry,
            retryMessage -> logger.debug("[{}] {} {}", jobId, quantilesDocId, retryMessage)
        );
        String indexOrAlias = searchResponse.getHits().getHits().length > 0
            ? searchResponse.getHits().getHits()[0].getIndex()
            : AnomalyDetectorsIndex.jobStateIndexWriteAlias();

        Persistable persistable = new Persistable(indexOrAlias, quantiles.getJobId(), quantiles, quantilesDocId);
        persistable.persist(shouldRetry, AnomalyDetectorsIndex.jobStateIndexWriteAlias().equals(indexOrAlias));
    }

    /**
     * Persist the quantiles (async)
     */
    public void persistQuantiles(Quantiles quantiles, WriteRequest.RefreshPolicy refreshPolicy, ActionListener<DocWriteResponse> listener) {
        String quantilesDocId = Quantiles.documentId(quantiles.getJobId());

        // Step 2: Create or update the quantiles document:
        // - if the document did not exist, create the new one in the current write index
        // - if the document did exist, update it in the index where it resides (not necessarily the current write index)
        ActionListener<SearchResponse> searchFormerQuantilesDocListener = ActionListener.wrap(searchResponse -> {
            String indexOrAlias = searchResponse.getHits().getHits().length > 0
                ? searchResponse.getHits().getHits()[0].getIndex()
                : AnomalyDetectorsIndex.jobStateIndexWriteAlias();

            Persistable persistable = new Persistable(indexOrAlias, quantiles.getJobId(), quantiles, quantilesDocId);
            persistable.setRefreshPolicy(refreshPolicy);
            persistable.persistWithoutRetries(listener, AnomalyDetectorsIndex.jobStateIndexWriteAlias().equals(indexOrAlias));
        }, listener::onFailure);

        // Step 1: Search for existing quantiles document in .ml-state*
        SearchRequest searchRequest = buildQuantilesDocIdSearch(quantilesDocId);
        executeAsyncWithOrigin(
            client.threadPool().getThreadContext(),
            ML_ORIGIN,
            searchRequest,
            searchFormerQuantilesDocListener,
            client::search
        );
    }

    private static SearchRequest buildQuantilesDocIdSearch(String quantilesDocId) {
        return new SearchRequest(AnomalyDetectorsIndex.jobStateIndexPattern()).allowPartialSearchResults(false)
            .source(
                new SearchSourceBuilder().size(1)
                    .fetchSource(false)
                    .trackTotalHits(false)
                    .query(new BoolQueryBuilder().filter(new IdsQueryBuilder().addIds(quantilesDocId)))
            );
    }

    /**
     * Persist a model snapshot description
     */
    public BulkResponse persistModelSnapshot(
        ModelSnapshot modelSnapshot,
        WriteRequest.RefreshPolicy refreshPolicy,
        Supplier<Boolean> shouldRetry
    ) {
        Persistable persistable = new Persistable(
            AnomalyDetectorsIndex.resultsWriteAlias(modelSnapshot.getJobId()),
            modelSnapshot.getJobId(),
            modelSnapshot,
            ModelSnapshot.documentId(modelSnapshot)
        );
        persistable.setRefreshPolicy(refreshPolicy);
        return persistable.persist(shouldRetry, true);
    }

    /**
     * Persist the memory usage data (blocking)
     */
    public void persistModelSizeStats(ModelSizeStats modelSizeStats, Supplier<Boolean> shouldRetry) {
        String jobId = modelSizeStats.getJobId();
        logger.trace("[{}] Persisting model size stats, for size {}", jobId, modelSizeStats.getModelBytes());
        Persistable persistable = new Persistable(
            AnomalyDetectorsIndex.resultsWriteAlias(jobId),
            jobId,
            modelSizeStats,
            modelSizeStats.getId()
        );
        persistable.persist(shouldRetry, true);
    }

    /**
     * Persist the memory usage data
     */
    public void persistModelSizeStatsWithoutRetries(
        ModelSizeStats modelSizeStats,
        WriteRequest.RefreshPolicy refreshPolicy,
        ActionListener<DocWriteResponse> listener
    ) {
        String jobId = modelSizeStats.getJobId();
        logger.trace("[{}] Persisting model size stats, for size {}", jobId, modelSizeStats.getModelBytes());
        Persistable persistable = new Persistable(
            AnomalyDetectorsIndex.resultsWriteAlias(jobId),
            jobId,
            modelSizeStats,
            modelSizeStats.getId()
        );
        persistable.setRefreshPolicy(refreshPolicy);
        persistable.persistWithoutRetries(listener, true);
    }

    /**
     * Delete any existing interim results synchronously
     */
    public void deleteInterimResults(String jobId) {
        new JobDataDeleter(client, jobId).deleteInterimResults();
    }

    /**
     * Once all the job data has been written this function will be
     * called to commit the writes to the datastore.
     *
     * @param jobId The job ID.
     * @param commitType Which type of data will be committed?
     */
    public void commitWrites(String jobId, CommitType commitType) {
        commitWrites(jobId, EnumSet.of(commitType));
    }

    /**
     * Once all the job data has been written this function will be
     * called to commit the writes to the datastore.
     *
     * @param jobId The job ID.
     * @param commitTypes Which type(s) of data will be committed?
     */
    public void commitWrites(String jobId, Set<CommitType> commitTypes) {
        if (commitTypes.isEmpty()) {
            return;
        }
        List<String> indexNames = new ArrayList<>();
        if (commitTypes.contains(CommitType.RESULTS)) {
            // We refresh using the read alias in order to ensure all indices will
            // be refreshed even if a rollover occurs in between.
            indexNames.add(AnomalyDetectorsIndex.jobResultsAliasedName(jobId));
        }
        if (commitTypes.contains(CommitType.STATE)) {
            indexNames.add(AnomalyDetectorsIndex.jobStateIndexPattern());
        }
        if (commitTypes.contains(CommitType.ANNOTATIONS)) {
            // We refresh using the read alias in order to ensure all indices will
            // be refreshed even if a rollover occurs in between.
            indexNames.add(AnnotationIndex.READ_ALIAS_NAME);
        }

        // Refresh should wait for Lucene to make the data searchable
        logger.trace("[{}] ES API CALL: refresh indices {}", jobId, indexNames);
        RefreshRequest refreshRequest = new RefreshRequest(indexNames.toArray(String[]::new));
        refreshRequest.indicesOptions(IndicesOptions.lenientExpandOpen());
        try (ThreadContext.StoredContext ignore = client.threadPool().getThreadContext().stashWithOrigin(ML_ORIGIN)) {
            client.admin().indices().refresh(refreshRequest).actionGet();
        }
        logger.trace("[{}] ES API CALL: finished refresh indices {}", jobId, indexNames);
    }

    /**
     * Persist datafeed timing stats
     *
     * @param timingStats datafeed timing stats to persist
     * @param refreshPolicy refresh policy to apply
     * @param listener listener for response or error
     */
    public void persistDatafeedTimingStats(
        DatafeedTimingStats timingStats,
        WriteRequest.RefreshPolicy refreshPolicy,
        ActionListener<BulkResponse> listener
    ) {
        String jobId = timingStats.getJobId();
        logger.trace("[{}] Persisting datafeed timing stats", jobId);
        Persistable persistable = new Persistable(
            AnomalyDetectorsIndex.resultsWriteAlias(jobId),
            jobId,
            timingStats,
            new ToXContent.MapParams(Collections.singletonMap(ToXContentParams.FOR_INTERNAL_STORAGE, "true")),
            DatafeedTimingStats.documentId(timingStats.getJobId())
        );
        persistable.setRefreshPolicy(refreshPolicy);
        persistable.persist(() -> true, true, listener);
    }

    private static XContentBuilder toXContentBuilder(ToXContent obj, ToXContent.Params params) throws IOException {
        XContentBuilder builder = jsonBuilder();
        obj.toXContent(builder, params);
        return builder;
    }

    private class Persistable {

        private final String indexName;
        private final String jobId;
        private final ToXContent object;
        private final ToXContent.Params params;
        private final String id;
        private WriteRequest.RefreshPolicy refreshPolicy;

        Persistable(String indexName, String jobId, ToXContent object, String id) {
            this(indexName, jobId, object, ToXContent.EMPTY_PARAMS, id);
        }

        Persistable(String indexName, String jobId, ToXContent object, ToXContent.Params params, String id) {
            this.indexName = indexName;
            this.jobId = jobId;
            this.object = object;
            this.params = params;
            this.id = id;
            this.refreshPolicy = WriteRequest.RefreshPolicy.NONE;
        }

        void setRefreshPolicy(WriteRequest.RefreshPolicy refreshPolicy) {
            this.refreshPolicy = refreshPolicy;
        }

        BulkResponse persist(Supplier<Boolean> shouldRetry, boolean requireAlias) {
            final PlainActionFuture<BulkResponse> getResponseFuture = new PlainActionFuture<>();
            persist(shouldRetry, requireAlias, getResponseFuture);
            return getResponseFuture.actionGet();
        }

        void persist(Supplier<Boolean> shouldRetry, boolean requireAlias, ActionListener<BulkResponse> listener) {
            logCall();
            try {
                resultsPersisterService.indexWithRetry(
                    jobId,
                    indexName,
                    object,
                    params,
                    refreshPolicy,
                    id,
                    requireAlias,
                    shouldRetry,
                    retryMessage -> logger.debug("[{}] {} {}", jobId, id, retryMessage),
                    listener
                );
            } catch (IOException e) {
                logger.error(() -> format("[%s] Error writing [%s]", jobId, (id == null) ? "auto-generated ID" : id), e);
                IndexResponse.Builder notCreatedResponse = new IndexResponse.Builder();
                notCreatedResponse.setResult(Result.NOOP);
                listener.onResponse(
                    new BulkResponse(
                        new BulkItemResponse[] { BulkItemResponse.success(0, DocWriteRequest.OpType.INDEX, notCreatedResponse.build()) },
                        0
                    )
                );
            }
        }

        void persistWithoutRetries(ActionListener<DocWriteResponse> listener, boolean requireAlias) {
            logCall();

            try (XContentBuilder content = toXContentBuilder(object, params)) {
                IndexRequest indexRequest = new IndexRequest(indexName).id(id)
                    .source(content)
                    .setRefreshPolicy(refreshPolicy)
                    .setRequireAlias(requireAlias);
                executeAsyncWithOrigin(client.threadPool().getThreadContext(), ML_ORIGIN, indexRequest, listener, client::index);
            } catch (IOException e) {
                logger.error(() -> format("[%s] Error writing [%s]", jobId, (id == null) ? "auto-generated ID" : id), e);
                IndexResponse.Builder notCreatedResponse = new IndexResponse.Builder();
                notCreatedResponse.setResult(Result.NOOP);
                listener.onResponse(notCreatedResponse.build());
            }
        }

        private void logCall() {
            if (logger.isTraceEnabled()) {
                if (id != null) {
                    logger.trace("[{}] ES API CALL: to index {} with ID [{}]", jobId, indexName, id);
                } else {
                    logger.trace("[{}] ES API CALL: to index {} with auto-generated ID", jobId, indexName);
                }
            }
        }
    }
}
