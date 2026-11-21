package com.hackathon.alerting.common.json;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultJsonFieldLocatorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JsonFieldLocator locator = new DefaultJsonFieldLocator();

    @Test
    void resolvesExactPathsAndArrayIndices() {
        JsonNode event = JSON.readTree("""
                {"trade": {"id": "T-1", "legs": [{"ccy": "EUR"}, {"ccy": "USD"}]}, "empty": null}
                """);

        assertThat(locator.find(event, "trade.id", true)).hasValueSatisfying(n -> assertThat(n.asString()).isEqualTo("T-1"));
        assertThat(locator.find(event, "trade.legs.1.ccy", true)).hasValueSatisfying(n -> assertThat(n.asString()).isEqualTo("USD"));
        assertThat(locator.find(event, "trade.legs.5.ccy", true)).isEmpty();
        assertThat(locator.find(event, "empty", true)).isEmpty();
        assertThat(locator.find(event, "missing.path", true)).isEmpty();
        assertThat(locator.find(null, "trade.id", false)).isEmpty();
        assertThat(locator.find(event, " ", false)).isEmpty();
    }

    @Test
    void flexibleModeFallsBackToCaseInsensitiveKeySearchWhileStrictDoesNot() throws IOException {
        JsonNode alien = loadFixture("events/alien-schema.json");

        assertThat(locator.find(alien, "trade.notional", true)).isEmpty();
        assertThat(locator.find(alien, "trade.notional", false))
                .hasValueSatisfying(n -> assertThat(n.decimalValue()).isEqualByComparingTo("25000000"));
        assertThat(locator.find(alien, "instrument.ccy", false))
                .hasValueSatisfying(n -> assertThat(n.asString()).isEqualTo("USD"));
        assertThat(locator.find(alien, "Body.Deal.Economics.Notional", true))
                .hasValueSatisfying(n -> assertThat(n.decimalValue()).isEqualByComparingTo("25000000"));
    }

    @Test
    void flexibleModePrefersExactPathAndShallowerKeysAndSearchesArrays() {
        JsonNode event = JSON.readTree("""
                {
                  "nested": {"deep": {"status": "DEEP"}},
                  "items": [{"code": "A1"}, {"code": "B2"}],
                  "status": "TOP"
                }
                """);

        assertThat(locator.find(event, "nested.deep.status", false))
                .hasValueSatisfying(n -> assertThat(n.asString()).isEqualTo("DEEP"));
        assertThat(locator.find(event, "other.status", false))
                .hasValueSatisfying(n -> assertThat(n.asString()).isEqualTo("TOP"));
        assertThat(locator.find(event, "record.code", false))
                .hasValueSatisfying(n -> assertThat(n.asString()).isEqualTo("A1"));
    }

    private static JsonNode loadFixture(String resourcePath) throws IOException {
        try (InputStream in = DefaultJsonFieldLocatorTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
            assertThat(in).as(resourcePath).isNotNull();
            return JSON.readTree(in);
        }
    }
}
