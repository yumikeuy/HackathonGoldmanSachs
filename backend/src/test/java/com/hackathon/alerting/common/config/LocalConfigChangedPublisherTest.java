package com.hackathon.alerting.common.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalConfigChangedPublisherTest {

    private final List<Object> published = new ArrayList<>();
    private final ConfigChangedPublisher publisher = new LocalConfigChangedPublisher(published::add);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void publishesImmediatelyOutsideTransactions() {
        publisher.publish(ConfigKinds.RULES);

        assertThat(published).containsExactly(new ConfigChangedEvent(ConfigKinds.RULES));
    }

    @Test
    void publishesOnlyAfterCommitInsideTransactions() {
        TransactionSynchronizationManager.initSynchronization();

        publisher.publish(ConfigKinds.TEAMS);
        assertThat(published).isEmpty();

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(published).containsExactly(new ConfigChangedEvent(ConfigKinds.TEAMS));
    }
}
