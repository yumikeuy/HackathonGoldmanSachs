package com.hackathon.alerting.ingest.lag;

import com.hackathon.alerting.common.config.KafkaTopics;
import com.hackathon.alerting.common.web.ServiceUnavailableException;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@code GET /api/ingest/lag}: how far the event processor ({@value KafkaTopics#PROCESSOR_GROUP}) is behind on
 * {@code events.ingest} — per partition, end offset minus committed offset.
 */
@RestController
public class IngestLagController {

    static final long TIMEOUT_SECONDS = 5;

    private final KafkaAdmin kafkaAdmin;

    public IngestLagController(KafkaAdmin kafkaAdmin) {
        this.kafkaAdmin = kafkaAdmin;
    }

    @GetMapping("/api/ingest/lag")
    public IngestLag lag() {
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            TopicDescription topic = admin.describeTopics(List.of(KafkaTopics.EVENTS_INGEST))
                    .allTopicNames().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).get(KafkaTopics.EVENTS_INGEST);
            List<TopicPartition> partitions = topic.partitions().stream()
                    .map(p -> new TopicPartition(KafkaTopics.EVENTS_INGEST, p.partition()))
                    .toList();

            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> ends = admin
                    .listOffsets(partitions.stream().collect(Collectors.toMap(Function.identity(), p -> OffsetSpec.latest())))
                    .all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Map<TopicPartition, OffsetAndMetadata> committed = admin
                    .listConsumerGroupOffsets(KafkaTopics.PROCESSOR_GROUP)
                    .partitionsToOffsetAndMetadata().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            List<PartitionLag> result = new ArrayList<>();
            for (TopicPartition partition : partitions) {
                long end = ends.get(partition).offset();
                OffsetAndMetadata offset = committed.get(partition);
                long lag = Math.max(0, end - (offset == null ? 0 : offset.offset()));
                result.add(new PartitionLag(partition.topic(), partition.partition(), lag));
            }
            result.sort(Comparator.comparingInt(PartitionLag::partition));
            return new IngestLag(KafkaTopics.PROCESSOR_GROUP, result.stream().mapToLong(PartitionLag::lag).sum(), result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException("Interrupted while asking Kafka for the lag", e);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            throw new ServiceUnavailableException("Kafka is unavailable, retry later", e);
        }
    }

    /** {@code IngestLag} of openapi.yaml. */
    public record IngestLag(String groupId, long totalLag, List<PartitionLag> partitions) {
    }

    public record PartitionLag(String topic, int partition, long lag) {
    }
}
