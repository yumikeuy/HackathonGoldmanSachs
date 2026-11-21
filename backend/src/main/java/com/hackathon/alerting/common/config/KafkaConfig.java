package com.hackathon.alerting.common.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.hibernate.exception.JDBCConnectionException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonDelegatingErrorHandler;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.FixedBackOff;

import java.net.ConnectException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Internal Kafka topics and the error handling of the {@code events.ingest} listener (AGENTS.md §3).
 *
 * <p>The {@link CommonErrorHandler} bean is applied by Spring Boot to the default listener container factory,
 * so Role 2's {@code @KafkaListener} gets it without any configuration:
 * <ul>
 *   <li>a record that keeps failing (thrown as {@code BatchListenerFailedException(index)}) is retried after
 *       1, 2 and 5 s and then published to {@code events.ingest.DLT};</li>
 *   <li>if anywhere in the cause chain there is a lost connection to Postgres or Redis, the batch is retried
 *       every 5 s forever and never goes to the DLT — the data is fine, the infrastructure is not.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConfig {

    /** Back-off before each retry of a failing record; then the record goes to the DLT. */
    static final List<Duration> RECORD_RETRY_DELAYS = List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(5));

    /** Retry interval while Postgres or Redis is unreachable. */
    static final Duration CONNECTION_RETRY_INTERVAL = Duration.ofSeconds(5);

    /** Exceptions that mean "the infrastructure is down", looked up anywhere in the cause chain. */
    static final List<Class<? extends Throwable>> CONNECTION_FAILURES = List.of(
            DataAccessResourceFailureException.class,  // incl. RedisConnectionFailureException, CannotGetJdbcConnectionException
            CannotCreateTransactionException.class,
            JDBCConnectionException.class,
            SQLTransientConnectionException.class,
            ConnectException.class);

    @Bean
    NewTopic eventsIngestTopic() {
        return TopicBuilder.name(KafkaTopics.EVENTS_INGEST)
                .partitions(6)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(Duration.ofHours(24).toMillis()))
                .build();
    }

    @Bean
    NewTopic eventsIngestDltTopic() {
        return TopicBuilder.name(KafkaTopics.EVENTS_INGEST_DLT)
                .partitions(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(Duration.ofDays(7).toMillis()))
                .build();
    }

    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        // The DLT has a single partition: let the producer pick it instead of mirroring the source partition.
        DeadLetterPublishingRecoverer toDlt = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(KafkaTopics.EVENTS_INGEST_DLT, -1));
        DefaultErrorHandler retryThenDlt = new DefaultErrorHandler(toDlt, new DelaySequenceBackOff(RECORD_RETRY_DELAYS));

        DefaultErrorHandler retryForever = new DefaultErrorHandler(
                new FixedBackOff(CONNECTION_RETRY_INTERVAL.toMillis(), FixedBackOff.UNLIMITED_ATTEMPTS));

        CommonDelegatingErrorHandler handler = new CommonDelegatingErrorHandler(retryThenDlt);
        Map<Class<? extends Throwable>, CommonErrorHandler> delegates = new LinkedHashMap<>();
        CONNECTION_FAILURES.forEach(type -> delegates.put(type, retryForever));
        handler.setErrorHandlers(delegates);
        handler.setCauseChainTraversing(true);
        return handler;
    }

    /** A {@link BackOff} that waits the given delays in order and then stops. */
    record DelaySequenceBackOff(List<Duration> delays) implements BackOff {

        @Override
        public BackOffExecution start() {
            return new BackOffExecution() {
                private int attempt;

                @Override
                public long nextBackOff() {
                    return attempt < delays.size() ? delays.get(attempt++).toMillis() : STOP;
                }
            };
        }
    }
}
