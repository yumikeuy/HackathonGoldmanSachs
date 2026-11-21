package com.hackathon.alerting.dev;

import com.hackathon.alerting.common.web.InvalidRequestException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands placeholders in the string values of a generator template, so generated events vary:
 *
 * <table>
 *   <tr><th>Placeholder</th><th>Becomes</th></tr>
 *   <tr><td>{@code {{seq}}}</td><td>sequence number of the event in the run: 1, 2, 3 …</td></tr>
 *   <tr><td>{@code {{uuid}}}</td><td>a random UUID</td></tr>
 *   <tr><td>{@code {{now}}}</td><td>current time, ISO-8601 UTC</td></tr>
 *   <tr><td>{@code {{randInt:MIN:MAX}}}</td><td>random integer, both bounds inclusive</td></tr>
 *   <tr><td>{@code {{oneOf:A|B|C}}}</td><td>one of the listed values at random</td></tr>
 * </table>
 *
 * <p>Placeholders work anywhere inside string values, at any depth; keys are never changed. A string that is
 * <b>exactly</b> {@code {{seq}}} or {@code {{randInt:…}}} becomes a JSON number, so numeric conditions also work in
 * strict mode. Unknown or malformed placeholders are rejected by {@link #validate} before a run starts.
 */
@Component
public class TemplateExpander {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z]+)(?::([^}]*))?\\s*}}");
    private static final List<String> NAMES = List.of("seq", "uuid", "now", "randInt", "oneOf");

    private final Clock clock;

    public TemplateExpander(Clock clock) {
        this.clock = clock;
    }

    /** @throws InvalidRequestException naming the first unknown or malformed placeholder */
    public void validate(JsonNode template) {
        forEachString(template, text -> {
            Matcher matcher = PLACEHOLDER.matcher(text);
            while (matcher.find()) {
                checkPlaceholder(matcher.group(1), matcher.group(2), matcher.group());
            }
        });
    }

    /**
     * @param template a validated template
     * @param seq      1-based number of the event in the run
     * @return a new event; the template is not modified
     */
    public JsonNode expand(JsonNode template, long seq) {
        if (template.isString()) {
            return expandString(template.asString(), seq);
        }
        if (template.isObject()) {
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<String, JsonNode> field : template.properties()) {
                copy.set(field.getKey(), expand(field.getValue(), seq));
            }
            return copy;
        }
        if (template.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            template.values().forEach(item -> copy.add(expand(item, seq)));
            return copy;
        }
        return template;
    }

    private JsonNode expandString(String text, long seq) {
        Matcher whole = PLACEHOLDER.matcher(text);
        if (whole.matches() && ("seq".equals(whole.group(1)) || "randInt".equals(whole.group(1)))) {
            return JsonNodeFactory.instance.numberNode(Long.parseLong(value(whole.group(1), whole.group(2), seq)));
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(value(matcher.group(1), matcher.group(2), seq)));
        }
        matcher.appendTail(out);
        return JsonNodeFactory.instance.stringNode(out.toString());
    }

    private String value(String name, String argument, long seq) {
        return switch (name) {
            case "seq" -> Long.toString(seq);
            case "uuid" -> UUID.randomUUID().toString();
            case "now" -> clock.instant().toString();
            case "randInt" -> {
                long[] bounds = bounds(argument);
                yield Long.toString(ThreadLocalRandom.current().nextLong(bounds[0], bounds[1] + 1));
            }
            case "oneOf" -> {
                String[] options = argument.split("\\|", -1);
                yield options[ThreadLocalRandom.current().nextInt(options.length)];
            }
            default -> throw new IllegalStateException("Unvalidated placeholder " + name);
        };
    }

    private static void checkPlaceholder(String name, String argument, String text) {
        if (!NAMES.contains(name)) {
            throw new InvalidRequestException("template: unknown placeholder " + text + "; known: " + NAMES);
        }
        boolean takesArgument = "randInt".equals(name) || "oneOf".equals(name);
        if (takesArgument == (argument == null)) {
            throw new InvalidRequestException("template: " + text + (takesArgument ? " needs an argument" : " takes no argument"));
        }
        if ("randInt".equals(name)) {
            try {
                bounds(argument);
            } catch (IllegalArgumentException e) {
                throw new InvalidRequestException("template: " + text + " must be {{randInt:MIN:MAX}} with MIN <= MAX");
            }
        }
        if ("oneOf".equals(name) && argument.isEmpty()) {
            throw new InvalidRequestException("template: " + text + " needs at least one option");
        }
    }

    /** {@code "1:100"} → {@code [1, 100]}. */
    private static long[] bounds(String argument) {
        String[] parts = argument.split(":", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("expected MIN:MAX");
        }
        long min = Long.parseLong(parts[0].strip());
        long max = Long.parseLong(parts[1].strip());
        if (min > max) {
            throw new IllegalArgumentException("MIN > MAX");
        }
        return new long[]{min, max};
    }

    private static void forEachString(JsonNode node, Consumer<String> action) {
        if (node == null) {
            return;
        }
        if (node.isString()) {
            action.accept(node.asString());
        } else if (node.isContainer()) {
            node.values().forEach(child -> forEachString(child, action));
        }
    }
}
