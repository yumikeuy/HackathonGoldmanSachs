package com.hackathon.alerting.ingest.recent;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRecentEventsTest {

    @Test
    void keepsTheNewestEventsUpToCapacity() {
        RecentEvents recent = new InMemoryRecentEvents();
        for (int i = 0; i < RecentEvents.CAPACITY + 5; i++) {
            recent.add(JsonNodeFactory.instance.numberNode(i));
        }

        assertThat(recent.latest(3)).extracting(n -> n.asInt()).containsExactly(1004, 1003, 1002);
        assertThat(recent.latest(5000)).hasSize(RecentEvents.CAPACITY);
        assertThat(recent.latest(5000).getLast().asInt()).isEqualTo(5);
    }
}
