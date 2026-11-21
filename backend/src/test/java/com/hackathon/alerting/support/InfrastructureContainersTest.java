package com.hackathon.alerting.support;

import com.hackathon.alerting.common.config.KafkaTopics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The shared container configurations start and the application connects to all three. */
@SpringBootTest
@Import({PostgresTestConfiguration.class, KafkaTestConfiguration.class, RedisTestConfiguration.class})
class InfrastructureContainersTest {

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private StringRedisTemplate redis;

    @Test
    void connectsToKafkaAndRedis() throws Exception {
        var result = kafka.send(KafkaTopics.EVENTS_INGEST, "src", "{}").get(30, TimeUnit.SECONDS);
        assertThat(result.getRecordMetadata().topic()).isEqualTo(KafkaTopics.EVENTS_INGEST);

        redis.opsForValue().set("ping", "pong");
        assertThat(redis.opsForValue().get("ping")).isEqualTo("pong");
    }
}
