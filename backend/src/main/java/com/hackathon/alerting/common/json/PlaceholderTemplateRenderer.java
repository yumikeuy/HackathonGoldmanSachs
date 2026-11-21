package com.hackathon.alerting.common.json;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default {@link TemplateRenderer} implementation (owner: Role 3): resolves {@code {{path}}}
 * placeholders through {@link JsonFieldLocator} in flexible mode, rendering absent or {@code null}
 * values as empty strings.
 */
@Component
public class PlaceholderTemplateRenderer implements TemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([^}\\s]+)\\s*}}");

    private final JsonFieldLocator fields;

    public PlaceholderTemplateRenderer(JsonFieldLocator fields) {
        this.fields = fields;
    }

    @Override
    public String render(String template, JsonNode event) {
        if (template == null) {
            return "";
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = fields.find(event, matcher.group(1), false)
                    .map(node -> node.isValueNode() ? node.asString() : node.toString())
                    .orElse("");
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
