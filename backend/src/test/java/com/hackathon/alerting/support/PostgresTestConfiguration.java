package com.hackathon.alerting.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL 16 in Testcontainers, wired as the application's datasource via {@link ServiceConnection}.
 * Flyway applies the real migrations on startup.
 *
 * <p>Import it into every database test ({@code @Import(PostgresTestConfiguration.class)}): identical
 * configurations share one cached Spring context and therefore one container per test run.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer("postgres:16");
    }
}
