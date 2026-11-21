package com.hackathon.alerting.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;

/** Redis 7 in Testcontainers, wired via {@link ServiceConnection} into {@code spring.data.redis.*}. */
@TestConfiguration(proxyBeanMethods = false)
public class RedisTestConfiguration {

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>("redis:7").withExposedPorts(6379);
    }
}
