package com.hackathon.alerting.common.condition;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Supported leaf comparison operators in condition trees (AGENTS.md §5).
 */
public enum ConditionOperator {
    EQ("=="),
    NE("!="),
    GT(">"),
    GTE(">="),
    LT("<"),
    LTE("<="),
    IN("in"),
    NOT_IN("not_in"),
    CONTAINS("contains"),
    REGEX("regex"),
    GLOB("glob"),
    EXISTS("exists");

    private static final Map<String, ConditionOperator> BY_SYMBOL = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(ConditionOperator::symbol, Function.identity()));

    private final String symbol;

    ConditionOperator(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    public static Optional<ConditionOperator> fromSymbol(String symbol) {
        return Optional.ofNullable(BY_SYMBOL.get(symbol));
    }
}
