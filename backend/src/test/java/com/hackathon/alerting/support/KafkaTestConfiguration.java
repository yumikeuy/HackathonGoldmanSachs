package com.hackathon.alerting.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Kafka (KRaft, same image family as docker-compose) in Testcontainers, wired via {@link ServiceConnection}
 * into {@code spring.kafka.bootstrap-servers}. Import together with the other configurations an
 * integration test needs: {@code @Import({PostgresTestConfiguration.class, KafkaTestConfiguration.class})}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class KafkaTestConfiguration {

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaContainer("apache/kafka:4.0.0");
    }
}
