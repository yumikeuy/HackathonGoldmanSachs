package com.hackathon.alerting.common.json;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceholderTemplateRendererTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final TemplateRenderer renderer = new PlaceholderTemplateRenderer(new ExactPathFieldLocator());

    @Test
    void substitutesValuesAndBlanksMissingOnes() {
        var event = JSON.readTree("{\"trade\":{\"id\":\"T-1\",\"notional\":15000000,\"tags\":[\"a\"],\"note\":null}}");

        assertThat(renderer.render("Rejected {{trade.id}} ({{ trade.notional }}) {{trade.tags}} {{trade.note}}{{missing}}$", event))
                .isEqualTo("Rejected T-1 (15000000) [\"a\"] $");
        assertThat(renderer.render(null, event)).isEmpty();
    }

    @Test
    void substitutesPlaceholdersAcrossAlienSchemasUsingFlexibleLookup() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("events/alien-schema.json")) {
            JsonNode alien = JSON.readTree(in);
            assertThat(renderer.render("Deal {{deal.ref}}: {{NOTIONAL}} {{trade.ccy}}", alien))
                    .isEqualTo("Deal FX-778: 25000000 USD");
        }
    }
}
