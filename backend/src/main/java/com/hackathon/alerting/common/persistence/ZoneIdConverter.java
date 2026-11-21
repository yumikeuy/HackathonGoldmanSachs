package com.hackathon.alerting.common.persistence;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.ZoneId;

/**
 * Stores a {@link ZoneId} as its IANA id ({@code Europe/Warsaw}).
 * An invalid value in the database fails on read — better than silently texting someone at night.
 */
@Converter(autoApply = true)
public class ZoneIdConverter implements AttributeConverter<ZoneId, String> {

    @Override
    public String convertToDatabaseColumn(ZoneId zone) {
        return zone == null ? null : zone.getId();
    }

    @Override
    public ZoneId convertToEntityAttribute(String id) {
        return id == null ? null : ZoneId.of(id);
    }
}
