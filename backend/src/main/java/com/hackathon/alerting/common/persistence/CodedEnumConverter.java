package com.hackathon.alerting.common.persistence;

import jakarta.persistence.AttributeConverter;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Base JPA converter for {@link CodedEnum}: enum ↔ {@link CodedEnum#code()}.
 *
 * <p>Example subclass:
 * <pre>{@code
 * @Converter(autoApply = true)
 * public static class JpaConverter extends CodedEnumConverter<SourceType> {
 *     public JpaConverter() { super(SourceType.class); }
 * }
 * }</pre>
 *
 * @param <E> enum type
 */
public abstract class CodedEnumConverter<E extends Enum<E> & CodedEnum> implements AttributeConverter<E, String> {

    private final Class<E> type;
    private final Map<String, E> byCode;

    protected CodedEnumConverter(Class<E> type) {
        this.type = Objects.requireNonNull(type, "type");
        this.byCode = Arrays.stream(type.getEnumConstants())
                .collect(Collectors.toUnmodifiableMap(CodedEnum::code, Function.identity()));
    }

    @Override
    public String convertToDatabaseColumn(E value) {
        return value == null ? null : value.code();
    }

    @Override
    public E convertToEntityAttribute(String code) {
        if (code == null) {
            return null;
        }
        E value = byCode.get(code);
        if (value == null) {
            throw new IllegalArgumentException("Unknown " + type.getSimpleName() + " code in database: '" + code + "'");
        }
        return value;
    }
}
