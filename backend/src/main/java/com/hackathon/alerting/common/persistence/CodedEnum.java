package com.hackathon.alerting.common.persistence;

/**
 * An enum whose database value differs from the constant name
 * (e.g. lowercase {@code http}, {@code while_open}).
 *
 * <p>Each such enum declares its own subclass of {@link CodedEnumConverter}.
 * Enums stored as their uppercase constant names (OPEN, EMAIL …) do not need this interface —
 * {@code @Enumerated(EnumType.STRING)} is enough for them.
 */
public interface CodedEnum {

    /** The value stored in the database column and used in JSON. */
    String code();
}
