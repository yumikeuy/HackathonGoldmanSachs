package com.hackathon.alerting.delivery.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalTime;
import java.util.List;
import java.util.Objects;

/**
 * Value of {@code users.working_hours}: {@code {"from":"08:00","to":"20:00","days":[1,2,3,4,5]}}.
 * Times are in the user's time zone ({@code users.timezone}).
 *
 * @param from start of the working day (inclusive)
 * @param to   end of the working day (exclusive)
 * @param days working days in ISO-8601 numbering: 1 = Monday … 7 = Sunday
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkingHours(LocalTime from, LocalTime to, List<Integer> days) {

    public WorkingHours {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        days = days == null ? List.of() : List.copyOf(days);
        for (Integer day : days) {
            if (day == null || day < 1 || day > 7) {
                throw new IllegalArgumentException("working_hours.days must contain ISO days 1..7, got " + days);
            }
        }
    }
}
