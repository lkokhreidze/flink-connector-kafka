/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.connector.kafka.source;

import org.apache.flink.annotation.Internal;
import org.apache.flink.annotation.PublicEvolving;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.connector.source.Boundedness;
import org.apache.flink.api.connector.source.Source;
import org.apache.flink.api.connector.source.SourceReader;
import org.apache.flink.api.connector.source.SourceReaderContext;
import org.apache.flink.api.connector.source.SplitEnumerator;
import org.apache.flink.api.connector.source.SplitEnumeratorContext;
import org.apache.flink.api.java.typeutils.ResultTypeQueryable;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.connector.base.source.reader.RecordsWithSplitIds;
import org.apache.flink.connector.base.source.reader.synchronization.FutureCompletingBlockingQueue;
import org.apache.flink.connector.kafka.lineage.DefaultKafkaDatasetFacet;
import org.apache.flink.connector.kafka.lineage.DefaultKafkaDatasetIdentifier;
import org.apache.flink.connector.kafka.lineage.DefaultTypeDatasetFacet;
import org.apache.flink.connector.kafka.lineage.KafkaDatasetIdentifierProvider;
import org.apache.flink.connector.kafka.lineage.LineageUtil;
import org.apache.flink.connector.kafka.source.enumerator.KafkaSourceEnumState;
import org.apache.flink.connector.kafka.source.enumerator.KafkaSourceEnumStateSerializer;
import org.apache.flink.connector.kafka.source.enumerator.KafkaSourceEnumerator;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.connector.kafka.source.enumerator.subscriber.KafkaSubscriber;
import org.apache.flink.connector.kafka.source.metrics.KafkaSourceReaderMetrics;
import org.apache.flink.connector.kafka.source.reader.KafkaPartitionSplitReader;
import org.apache.flink.connector.kafka.source.reader.KafkaRecordEmitter;
import org.apache.flink.connector.kafka.source.reader.KafkaSourceReader;
import org.apache.flink.connector.kafka.source.reader.deserializer.KafkaRecordDeserializationSchema;
import org.apache.flink.connector.kafka.source.reader.fetcher.KafkaSourceFetcherManager;
import org.apache.flink.connector.kafka.source.split.KafkaPartitionSplit;
import org.apache.flink.connector.kafka.source.split.KafkaPartitionSplitSerializer;
import org.apache.flink.core.io.SimpleVersionedSerializer;
import org.apache.flink.metrics.MetricGroup;
import org.apache.flink.streaming.api.lineage.LineageVertexProvider;
import org.apache.flink.streaming.api.lineage.SourceLineageVertex;
import org.apache.flink.util.UserCodeClassLoader;
import org.apache.flink.util.function.SerializableSupplier;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The Source implementation of Kafka. Please use a {@link KafkaSourceBuilder} to construct a {@link
 * KafkaSource}. The following example shows how to create a KafkaSource emitting records of <code>
 * String</code> type.
 *
 * <pre>{@code
 * KafkaSource<String> source = KafkaSource
 *     .<String>builder()
 *     .setBootstrapServers(KafkaSourceTestEnv.brokerConnectionStrings)
 *     .setGroupId("MyGroup")
 *     .setTopics(Arrays.asList(TOPIC1, TOPIC2))
 *     .setDeserializer(new TestingKafkaRecordDeserializationSchema())
 *     .setStartingOffsets(OffsetsInitializer.earliest())
 *     .build();
 * }</pre>
 *
 * <p>{@link org.apache.flink.connector.kafka.source.enumerator.KafkaSourceEnumerator} only supports
 * adding new splits and not removing splits in split discovery.
 *
 * <p>See {@link KafkaSourceBuilder} for more details on how to configure this source.
 *
 * @param <OUT> the output type of the source.
 */
@PublicEvolving
public class KafkaSource<OUT>
        implements LineageVertexProvider,
                Source<OUT, KafkaPartitionSplit, KafkaSourceEnumState>,
                ResultTypeQueryable<OUT> {
    private static final Logger LOG = LoggerFactory.getLogger(KafkaSource.class);
    private static final long serialVersionUID = -8755372893283732098L;
    // Users can choose only one of the following ways to specify the topics to consume from.
    private final KafkaSubscriber subscriber;
    // Users can specify the starting / stopping offset initializer.
    private final OffsetsInitializer startingOffsetsInitializer;
    private final OffsetsInitializer stoppingOffsetsInitializer;
    // Boundedness
    private final Boundedness boundedness;
    private final KafkaRecordDeserializationSchema<OUT> deserializationSchema;
    // The configurations.
    private final Properties props;
    // Client rackId callback
    private final SerializableSupplier<String> rackIdSupplier;
    private final KafkaConsumerFactory kafkaConsumerFactory;

    KafkaSource(
            KafkaSubscriber subscriber,
            OffsetsInitializer startingOffsetsInitializer,
            @Nullable OffsetsInitializer stoppingOffsetsInitializer,
            Boundedness boundedness,
            KafkaRecordDeserializationSchema<OUT> deserializationSchema,
            Properties props,
            SerializableSupplier<String> rackIdSupplier,
            KafkaConsumerFactory kafkaConsumerFactory) {
        this.subscriber = subscriber;
        this.startingOffsetsInitializer = startingOffsetsInitializer;
        this.stoppingOffsetsInitializer = stoppingOffsetsInitializer;
        this.boundedness = boundedness;
        this.deserializationSchema = deserializationSchema;
        this.props = props;
        this.rackIdSupplier = rackIdSupplier;
        this.kafkaConsumerFactory = kafkaConsumerFactory;
    }

    /**
     * Get a kafkaSourceBuilder to build a {@link KafkaSource}.
     *
     * @return a Kafka source builder.
     */
    public static <OUT> KafkaSourceBuilder<OUT> builder() {
        return new KafkaSourceBuilder<>();
    }

    @Override
    public Boundedness getBoundedness() {
        return this.boundedness;
    }

    @Internal
    @Override
    public SourceReader<OUT, KafkaPartitionSplit> createReader(SourceReaderContext readerContext)
            throws Exception {
        return createReader(readerContext, (ignore) -> {});
    }

    @VisibleForTesting
    SourceReader<OUT, KafkaPartitionSplit> createReader(
            SourceReaderContext readerContext, Consumer<Collection<String>> splitFinishedHook)
            throws Exception {
        FutureCompletingBlockingQueue<RecordsWithSplitIds<ConsumerRecord<byte[], byte[]>>>
                elementsQueue = new FutureCompletingBlockingQueue<>();
        deserializationSchema.open(
                new DeserializationSchema.InitializationContext() {
                    @Override
                    public MetricGroup getMetricGroup() {
                        return readerContext.metricGroup().addGroup("deserializer");
                    }

                    @Override
                    public UserCodeClassLoader getUserCodeClassLoader() {
                        return readerContext.getUserCodeClassLoader();
                    }
                });
        final KafkaSourceReaderMetrics kafkaSourceReaderMetrics =
                new KafkaSourceReaderMetrics(readerContext.metricGroup());

        Supplier<KafkaPartitionSplitReader> splitReaderSupplier =
                () ->
                        new KafkaPartitionSplitReader(
                                props,
                                readerContext,
                                kafkaSourceReaderMetrics,
                                Optional.ofNullable(rackIdSupplier)
                                        .map(Supplier::get)
                                        .orElse(null),
                                kafkaConsumerFactory);
        KafkaRecordEmitter<OUT> recordEmitter = new KafkaRecordEmitter<>(deserializationSchema);

        return new KafkaSourceReader<>(
                elementsQueue,
                new KafkaSourceFetcherManager(
                        elementsQueue, splitReaderSupplier::get, splitFinishedHook),
                recordEmitter,
                toConfiguration(props),
                readerContext,
                kafkaSourceReaderMetrics);
    }

    @Internal
    @Override
    public SplitEnumerator<KafkaPartitionSplit, KafkaSourceEnumState> createEnumerator(
            SplitEnumeratorContext<KafkaPartitionSplit> enumContext) {
        return new KafkaSourceEnumerator(
                subscriber,
                startingOffsetsInitializer,
                stoppingOffsetsInitializer,
                props,
                enumContext,
                boundedness);
    }

    @Internal
    @Override
    public SplitEnumerator<KafkaPartitionSplit, KafkaSourceEnumState> restoreEnumerator(
            SplitEnumeratorContext<KafkaPartitionSplit> enumContext,
            KafkaSourceEnumState checkpoint)
            throws IOException {
        return new KafkaSourceEnumerator(
                subscriber,
                startingOffsetsInitializer,
                stoppingOffsetsInitializer,
                props,
                enumContext,
                boundedness,
                checkpoint);
    }

    @Internal
    @Override
    public SimpleVersionedSerializer<KafkaPartitionSplit> getSplitSerializer() {
        return new KafkaPartitionSplitSerializer();
    }

    @Internal
    @Override
    public SimpleVersionedSerializer<KafkaSourceEnumState> getEnumeratorCheckpointSerializer() {
        return new KafkaSourceEnumStateSerializer();
    }

    @Override
    public TypeInformation<OUT> getProducedType() {
        return deserializationSchema.getProducedType();
    }

    // ----------- private helper methods ---------------

    private Configuration toConfiguration(Properties props) {
        Configuration config = new Configuration();
        props.stringPropertyNames().forEach(key -> config.setString(key, props.getProperty(key)));
        return config;
    }

    @VisibleForTesting
    Configuration getConfiguration() {
        return toConfiguration(props);
    }

    @VisibleForTesting
    KafkaSubscriber getKafkaSubscriber() {
        return subscriber;
    }

    @VisibleForTesting
    OffsetsInitializer getStoppingOffsetsInitializer() {
        return stoppingOffsetsInitializer;
    }

    @Override
    public SourceLineageVertex getLineageVertex() {
        if (!(subscriber instanceof KafkaDatasetIdentifierProvider)) {
            LOG.info("unable to determine topic identifier");
            return LineageUtil.sourceLineageVertexOf(Collections.emptyList());
        }

        Optional<DefaultKafkaDatasetIdentifier> topicsIdentifier =
                ((KafkaDatasetIdentifierProvider) subscriber).getDatasetIdentifier();

        if (!topicsIdentifier.isPresent()) {
            LOG.info("No topics' identifier returned from subscriber");
            return LineageUtil.sourceLineageVertexOf(Collections.emptyList());
        }

        DefaultKafkaDatasetFacet kafkaDatasetFacet =
                new DefaultKafkaDatasetFacet(topicsIdentifier.get(), props);

        String namespace = LineageUtil.namespaceOf(props);
        return LineageUtil.sourceLineageVertexOf(
                Collections.singletonList(
                        LineageUtil.datasetOf(
                                namespace,
                                kafkaDatasetFacet,
                                new DefaultTypeDatasetFacet(getProducedType()))));
    }
}
