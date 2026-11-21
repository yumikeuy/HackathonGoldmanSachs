package com.hackathon.alerting.ingest.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.hackathon.alerting.common.persistence.CodedEnum;
import com.hackathon.alerting.common.persistence.CodedEnumConverter;
import jakarta.persistence.Converter;

/**
 * Event source type. Lowercase in the database, in REST JSON and in the {@code events.ingest} envelope
 * ({@code sources.type}, envelope field {@code source_type}).
 */
public enum SourceType implements CodedEnum {

    /** Events arrive at {@code POST /api/ingest/{source}}. */
    HTTP("http"),
    /** External Kafka topic; the topic name is in {@code sources.config.topic}. */
    KAFKA("kafka"),
    /** Tail of a log file (bonus). */
    FILE("file");

    private final String code;

    SourceType(String code) {
        this.code = code;
    }

    @Override
    @JsonValue
    public String code() {
        return code;
    }

    /**
     * Parses a lowercase code as used in the database, JSON and the event envelope.
     *
     * @throws IllegalArgumentException for an unknown code
     */
    @JsonCreator
    public static SourceType fromCode(String code) {
        for (SourceType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown source type '" + code + "'");
    }

    /** JPA converter {@code SourceType ↔ 'http' | 'kafka' | 'file'}. */
    @Converter(autoApply = true)
    public static class JpaConverter extends CodedEnumConverter<SourceType> {
        public JpaConverter() {
            super(SourceType.class);
        }
    }
}
