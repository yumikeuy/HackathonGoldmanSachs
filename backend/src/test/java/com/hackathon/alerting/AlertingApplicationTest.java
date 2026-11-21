package com.hackathon.alerting;

import com.hackathon.alerting.common.condition.ConditionCompiler;
import com.hackathon.alerting.common.config.ConfigChangedPublisher;
import com.hackathon.alerting.common.json.JsonFieldLocator;
import com.hackathon.alerting.common.json.TemplateRenderer;
import com.hackathon.alerting.delivery.outbox.OutboxService;
import com.hackathon.alerting.ingest.recent.RecentEvents;
import com.hackathon.alerting.runbooks.RunbookService;
import com.hackathon.alerting.runbooks.audit.AuditService;
import com.hackathon.alerting.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The full application context starts with exactly one implementation of every cross-role contract
 * (AGENTS.md §8, step 0). Kafka and Redis are not needed for startup.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class AlertingApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void startsWithOneImplementationOfEveryContract() {
        for (Class<?> contract : new Class<?>[]{ConditionCompiler.class, JsonFieldLocator.class, TemplateRenderer.class,
                RunbookService.class, AuditService.class, OutboxService.class, ConfigChangedPublisher.class, RecentEvents.class}) {
            assertThat(context.getBeanNamesForType(contract)).as(contract.getSimpleName()).hasSize(1);
        }
    }
}
