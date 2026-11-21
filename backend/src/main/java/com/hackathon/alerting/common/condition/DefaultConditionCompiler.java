package com.hackathon.alerting.common.condition;

import com.hackathon.alerting.common.json.JsonFieldLocator;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Default {@link ConditionCompiler} implementation (owner: Role 3).
 *
 * <p>Validates the condition tree and compiles regex/glob patterns and numeric literals once per
 * {@link #compile} call into a thread-safe {@link Predicate}.
 */
@Component
public class DefaultConditionCompiler implements ConditionCompiler {

    private final JsonFieldLocator fields;

    public DefaultConditionCompiler(JsonFieldLocator fields) {
        this.fields = fields;
    }

    @Override
    public Predicate<JsonNode> compile(JsonNode condition, boolean strict) {
        if (condition == null || condition.isNull()) {
            return event -> true;
        }
        return compileNode(condition, "$", strict);
    }

    private Predicate<JsonNode> compileNode(JsonNode node, String where, boolean strict) {
        if (!node.isObject()) {
            throw new InvalidConditionException("Condition node at " + where + " must be an object");
        }
        if (node.has("and")) {
            if (node.size() != 1) {
                throw new InvalidConditionException("'and' node at " + where + " must not have other properties");
            }
            List<Predicate<JsonNode>> children = compileChildren(node.get("and"), where + ".and", strict);
            return event -> {
                for (Predicate<JsonNode> child : children) {
                    if (!child.test(event)) {
                        return false;
                    }
                }
                return true;
            };
        }
        if (node.has("or")) {
            if (node.size() != 1) {
                throw new InvalidConditionException("'or' node at " + where + " must not have other properties");
            }
            List<Predicate<JsonNode>> children = compileChildren(node.get("or"), where + ".or", strict);
            return event -> {
                for (Predicate<JsonNode> child : children) {
                    if (child.test(event)) {
                        return true;
                    }
                }
                return false;
            };
        }
        return compileLeaf(node, where, strict);
    }

    private List<Predicate<JsonNode>> compileChildren(JsonNode array, String where, boolean strict) {
        if (array == null || !array.isArray()) {
            throw new InvalidConditionException("'" + where + "' must not be null and must be an array");
        }
        if (array.isEmpty()) {
            throw new InvalidConditionException("'" + where + "' must not be empty");
        }
        List<Predicate<JsonNode>> result = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            result.add(compileNode(array.get(i), where + "[" + i + "]", strict));
        }
        return List.copyOf(result);
    }

    private Predicate<JsonNode> compileLeaf(JsonNode node, String where, boolean strict) {
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            String key = entry.getKey();
            if (!"path".equals(key) && !"op".equals(key) && !"value".equals(key)) {
                throw new InvalidConditionException("Unknown property '" + key + "' in condition leaf at " + where);
            }
        }
        JsonNode pathNode = node.get("path");
        JsonNode opNode = node.get("op");
        if (pathNode == null || !pathNode.isString() || pathNode.asString().isBlank()
                || opNode == null || !opNode.isString() || opNode.asString().isBlank()) {
            throw new InvalidConditionException("Condition leaf at " + where + " needs 'path' and 'op'");
        }
        String path = pathNode.asString();
        if (!isValidPath(path)) {
            throw new InvalidConditionException("Invalid path '" + path + "' at " + where);
        }
        String opSymbol = opNode.asString();
        ConditionOperator operator = ConditionOperator.fromSymbol(opSymbol)
                .orElseThrow(() -> new InvalidConditionException("Unknown operator '" + opSymbol + "' at " + where));
        JsonNode expected = node.get("value");

        return switch (operator) {
            case EQ -> compileEquality(path, expected, where, strict, true);
            case NE -> compileEquality(path, expected, where, strict, false);
            case GT, GTE, LT, LTE -> compileNumericComparison(path, operator, expected, where, strict);
            case IN -> compileMembership(path, expected, where, strict, true);
            case NOT_IN -> compileMembership(path, expected, where, strict, false);
            case CONTAINS -> compileContains(path, expected, where, strict);
            case REGEX -> compileRegex(path, expected, where, strict);
            case GLOB -> compileGlobPredicate(path, expected, where, strict);
            case EXISTS -> compileExists(path, expected, where, strict);
        };
    }

    private Predicate<JsonNode> compileEquality(String path, JsonNode expected, String where, boolean strict, boolean positive) {
        if (expected == null || expected.isNull()) {
            String op = positive ? "==" : "!=";
            throw new InvalidConditionException("Operator '" + op + "' at " + where + " requires a non-null 'value'");
        }
        return event -> fields.find(event, path, strict)
                .map(actual -> valuesEqual(actual, expected, strict) == positive)
                .orElse(false);
    }

    private Predicate<JsonNode> compileNumericComparison(String path, ConditionOperator op, JsonNode expected,
                                                         String where, boolean strict) {
        // Condition thresholds must always be JSON numbers so flipping team strictness later never breaks them.
        BigDecimal expectedNumber = extractNumber(expected, true)
                .orElseThrow(() -> new InvalidConditionException(
                        "Operator '" + op.symbol() + "' at " + where + " requires a numeric 'value'"));
        return event -> fields.find(event, path, strict)
                .flatMap(actual -> extractNumber(actual, strict))
                .map(actualNumber -> {
                    int cmp = actualNumber.compareTo(expectedNumber);
                    return switch (op) {
                        case GT -> cmp > 0;
                        case GTE -> cmp >= 0;
                        case LT -> cmp < 0;
                        case LTE -> cmp <= 0;
                        default -> false;
                    };
                })
                .orElse(false);
    }

    private Predicate<JsonNode> compileMembership(String path, JsonNode expected, String where, boolean strict, boolean positive) {
        String op = positive ? "in" : "not_in";
        if (expected == null || !expected.isArray() || expected.isEmpty()) {
            throw new InvalidConditionException("Operator '" + op + "' at " + where + " requires a non-empty array 'value'");
        }
        List<JsonNode> candidates = new ArrayList<>(expected.size());
        for (JsonNode candidate : expected.values()) {
            if (candidate == null || candidate.isNull()) {
                throw new InvalidConditionException("Operator '" + op + "' at " + where + " must not contain null elements");
            }
            candidates.add(candidate);
        }
        List<JsonNode> immutableCandidates = List.copyOf(candidates);
        return event -> fields.find(event, path, strict)
                .map(actual -> {
                    for (JsonNode candidate : immutableCandidates) {
                        if (valuesEqual(actual, candidate, strict)) {
                            return positive;
                        }
                    }
                    return !positive;
                })
                .orElse(false);
    }

    private Predicate<JsonNode> compileContains(String path, JsonNode expected, String where, boolean strict) {
        if (expected == null || !expected.isString()) {
            throw new InvalidConditionException("Operator 'contains' at " + where + " requires a string 'value'");
        }
        String needle = expected.asString();
        return event -> fields.find(event, path, strict)
                .filter(JsonNode::isString)
                .map(actual -> actual.asString().contains(needle))
                .orElse(false);
    }

    private Predicate<JsonNode> compileRegex(String path, JsonNode expected, String where, boolean strict) {
        if (expected == null || !expected.isString()) {
            throw new InvalidConditionException("Operator 'regex' at " + where + " requires a string 'value'");
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(expected.asString());
        } catch (PatternSyntaxException e) {
            throw new InvalidConditionException("Invalid regex at " + where + ": " + e.getDescription(), e);
        }
        return event -> fields.find(event, path, strict)
                .filter(JsonNode::isString)
                .map(actual -> pattern.matcher(actual.asString()).find())
                .orElse(false);
    }

    private Predicate<JsonNode> compileGlobPredicate(String path, JsonNode expected, String where, boolean strict) {
        if (expected == null || !expected.isString()) {
            throw new InvalidConditionException("Operator 'glob' at " + where + " requires a string 'value'");
        }
        Pattern pattern = globToRegex(expected.asString());
        return event -> fields.find(event, path, strict)
                .filter(JsonNode::isString)
                .map(actual -> pattern.matcher(actual.asString()).matches())
                .orElse(false);
    }

    private Predicate<JsonNode> compileExists(String path, JsonNode expected, String where, boolean strict) {
        if (expected != null && !expected.isNull() && !expected.isBoolean()) {
            throw new InvalidConditionException("Operator 'exists' at " + where + " requires a boolean 'value'");
        }
        boolean shouldExist = expected == null || expected.isNull() || expected.booleanValue();
        return event -> fields.find(event, path, strict).isPresent() == shouldExist;
    }

    private static boolean valuesEqual(JsonNode actual, JsonNode expected, boolean strict) {
        if (actual.isNumber() && expected.isNumber()) {
            Optional<BigDecimal> a = extractNumber(actual, true);
            Optional<BigDecimal> b = extractNumber(expected, true);
            return a.isPresent() && b.isPresent() && a.get().compareTo(b.get()) == 0;
        }
        if (!strict && (actual.isNumber() || expected.isNumber())) {
            Optional<BigDecimal> a = extractNumber(actual, false);
            Optional<BigDecimal> b = extractNumber(expected, false);
            if (a.isPresent() && b.isPresent()) {
                return a.get().compareTo(b.get()) == 0;
            }
        }
        return actual.equals(expected);
    }

    private static Optional<BigDecimal> extractNumber(JsonNode node, boolean strict) {
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        if (node.isNumber()) {
            try {
                return Optional.of(node.decimalValue());
            } catch (NumberFormatException | ArithmeticException e) {
                return Optional.empty();
            }
        }
        if (!strict && node.isString()) {
            String text = node.asString().trim();
            if (text.isEmpty()) {
                return Optional.empty();
            }
            try {
                return Optional.of(new BigDecimal(text));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static boolean isValidPath(String path) {
        if (!path.equals(path.trim())) {
            return false;
        }
        for (String segment : path.split("\\.", -1)) {
            if (segment.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static Pattern globToRegex(String glob) {
        StringBuilder regex = new StringBuilder(glob.length() + 8);
        regex.append('^');
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' || c == '?') {
                if (!literal.isEmpty()) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(c == '*' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        if (!literal.isEmpty()) {
            regex.append(Pattern.quote(literal.toString()));
        }
        regex.append('$');
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }
}
