package com.hackathon.alerting.delivery.model;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkingHoursTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void usesTheHoursAndMinutesFormatOfTheContract() {
        WorkingHours hours = new WorkingHours(LocalTime.of(8, 0), LocalTime.of(20, 0), List.of(1, 2, 3, 4, 5));

        assertThat(json.writeValueAsString(hours)).isEqualTo("{\"from\":\"08:00\",\"to\":\"20:00\",\"days\":[1,2,3,4,5]}");
        assertThat(json.readValue("{\"from\":\"08:00\",\"to\":\"20:00\",\"days\":[1]}", WorkingHours.class).to())
                .isEqualTo(LocalTime.of(20, 0));
    }
}
