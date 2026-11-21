package com.hackathon.alerting.common.condition;

import com.hackathon.alerting.common.json.JsonFieldLocator;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * STEP-0 STUB of {@link ConditionCompiler} (owner Role 3): supports {@code and}, {@code or} and the {@code ==}
 * operator by exact path; any other operator is rejected with {@link InvalidConditionException}.
 * Role 3 replaces the body with the full engine (all operators, flexible mode, number coercion).
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
        return compileNode(condition, "$");
    }

    private Predicate<JsonNode> compileNode(JsonNode node, String where) {
        if (!node.isObject()) {
            throw new InvalidConditionException("Condition node at " + where + " must be an object");
        }
        if (node.has("and")) {
            return children(node.get("and"), where + ".and").stream().reduce(event -> true, Predicate::and);
        }
        if (node.has("or")) {
            return children(node.get("or"), where + ".or").stream().reduce(event -> false, Predicate::or);
        }
        String path = node.path("path").asString(null);
        String op = node.path("op").asString(null);
        if (path == null || op == null) {
            throw new InvalidConditionException("Condition leaf at " + where + " needs 'path' and 'op'");
        }
        if (!"==".equals(op)) {
            throw new InvalidConditionException("Operator '" + op + "' at " + where + " is not implemented yet (step-0 stub)");
        }
        JsonNode expected = node.get("value");
        return event -> fields.find(event, path, true).map(actual -> actual.equals(expected)).orElse(false);
    }

    private List<Predicate<JsonNode>> children(JsonNode array, String where) {
        if (array == null || !array.isArray()) {
            throw new InvalidConditionException("'" + where + "' must be an array");
        }
        List<Predicate<JsonNode>> result = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            result.add(compileNode(array.get(i), where + "[" + i + "]"));
        }
        return result;
    }
}
