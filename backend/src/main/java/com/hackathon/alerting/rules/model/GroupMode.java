package com.hackathon.alerting.rules.model;

import com.hackathon.alerting.common.persistence.CodedEnum;
import com.hackathon.alerting.common.persistence.CodedEnumConverter;
import jakarta.persistence.Converter;

/** How repeats are grouped into one alert ({@code rules.group_mode}, lowercase in the database). */
public enum GroupMode implements CodedEnum {

    /** Repeats join the alert for as long as it is open (OPEN/ACKED). */
    WHILE_OPEN("while_open"),
    /** Every {@code dedup_window_sec} window starts a new alert: the window number is part of the fingerprint. */
    PER_WINDOW("per_window");

    private final String code;

    GroupMode(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }

    /** JPA converter {@code GroupMode ↔ 'while_open' | 'per_window'}. */
    @Converter(autoApply = true)
    public static class JpaConverter extends CodedEnumConverter<GroupMode> {
        public JpaConverter() {
            super(GroupMode.class);
        }
    }
}
