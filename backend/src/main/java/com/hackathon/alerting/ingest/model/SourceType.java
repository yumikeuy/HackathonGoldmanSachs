package com.hackathon.alerting.ingest.model;

import com.hackathon.alerting.common.persistence.CodedEnum;
import com.hackathon.alerting.common.persistence.CodedEnumConverter;
import jakarta.persistence.Converter;

/**
 * Event source type. Stored in lowercase in the database and in the {@code events.ingest} envelope
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
    public String code() {
        return code;
    }

    /** JPA converter {@code SourceType ↔ 'http' | 'kafka' | 'file'}. */
    @Converter(autoApply = true)
    public static class JpaConverter extends CodedEnumConverter<SourceType> {
        public JpaConverter() {
            super(SourceType.class);
        }
    }
}
