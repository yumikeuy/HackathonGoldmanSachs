package com.hackathon.alerting.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.util.backoff.BackOffExecution;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaConfigTest {

    @Test
    void recordRetriesWaitOneTwoFiveSecondsThenStop() {
        BackOffExecution execution = new KafkaConfig.DelaySequenceBackOff(KafkaConfig.RECORD_RETRY_DELAYS).start();

        assertThat(execution.nextBackOff()).isEqualTo(1000);
        assertThat(execution.nextBackOff()).isEqualTo(2000);
        assertThat(execution.nextBackOff()).isEqualTo(5000);
        assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);
    }
}
