package com.hackathon.alerting.common.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class RedisConfigChangedPublisherTest {

    private final List<Object> published = new ArrayList<>();
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ConfigChangedPublisher publisher = new RedisConfigChangedPublisher(redis, published::add);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void publishesToRedisImmediatelyOutsideTransactions() {
        publisher.publish(ConfigKinds.RULES);

        verify(redis).convertAndSend(RedisConfigChangedPublisher.CHANNEL, ConfigKinds.RULES);
        assertThat(published).isEmpty();
    }

    @Test
    void publishesOnlyAfterCommitInsideTransactions() {
        TransactionSynchronizationManager.initSynchronization();

        publisher.publish(ConfigKinds.TEAMS);
        verifyNoInteractions(redis);

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(redis).convertAndSend(RedisConfigChangedPublisher.CHANNEL, ConfigKinds.TEAMS);
    }

    @Test
    void fallsBackToTheLocalInstanceWhenRedisIsDown() {
        doThrow(new RedisConnectionFailureException("down")).when(redis).convertAndSend(anyString(), anyString());

        publisher.publish(ConfigKinds.USERS);

        assertThat(published).containsExactly(new ConfigChangedEvent(ConfigKinds.USERS));
    }
}
