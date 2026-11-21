package com.hackathon.alerting.common.json;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceholderTemplateRendererTest {

    private final TemplateRenderer renderer = new PlaceholderTemplateRenderer(new ExactPathFieldLocator());

    @Test
    void substitutesValuesAndBlanksMissingOnes() {
        var event = JsonMapper.builder().build().readTree("{\"trade\":{\"id\":\"T-1\",\"notional\":15000000,\"tags\":[\"a\"]}}");

        assertThat(renderer.render("Rejected {{trade.id}} ({{ trade.notional }}) {{trade.tags}} {{missing}}$", event))
                .isEqualTo("Rejected T-1 (15000000) [\"a\"] $");
        assertThat(renderer.render(null, event)).isEmpty();
    }
}
