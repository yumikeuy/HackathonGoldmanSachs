package com.hackathon.alerting.ingest.fields;

import com.hackathon.alerting.support.TestData;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FieldCatalogTest {

    @Test
    void flattensNestedObjectsIntoDottedPathsWithTypes() {
        var fields = FieldCatalog.flatten(TestData.json("""
                {"trade": {"id": "T-1", "notional": 15000000, "legs": [1, 2]}, "ok": true, "note": null}
                """));

        assertThat(fields.keySet()).containsExactly("trade", "trade.id", "trade.notional", "trade.legs", "ok", "note");
        assertThat(FieldCatalog.typeOf(fields.get("trade"))).isEqualTo("object");
        assertThat(FieldCatalog.typeOf(fields.get("trade.notional"))).isEqualTo("number");
        assertThat(FieldCatalog.typeOf(fields.get("trade.legs"))).isEqualTo("array");
        assertThat(FieldCatalog.typeOf(fields.get("ok"))).isEqualTo("boolean");
        assertThat(FieldCatalog.typeOf(fields.get("note"))).isEqualTo("null");
        assertThat(FieldCatalog.typeOf(fields.get("trade.id"))).isEqualTo("string");
    }

    @Test
    void ignoresNonObjectEvents() {
        assertThat(FieldCatalog.flatten(TestData.json("[1,2]"))).isEmpty();
        assertThat(FieldCatalog.flatten(TestData.json("\"text\""))).isEmpty();
    }

    @Test
    void doesNotSuggestIdsTimestampsHighCardinalityAndStrictPaths() {
        assertThat(FieldsController.looksLikeIdOrTime("id")).isTrue();
        assertThat(FieldsController.looksLikeIdOrTime("tradeId")).isTrue();
        assertThat(FieldsController.looksLikeIdOrTime("trade_id")).isTrue();
        assertThat(FieldsController.looksLikeIdOrTime("created_at")).isTrue();
        assertThat(FieldsController.looksLikeIdOrTime("updatedAt")).isTrue();
        assertThat(FieldsController.looksLikeIdOrTime("timestamp")).isTrue();
        assertThat(FieldsController.looksLikeIdOrTime("status")).isFalse();
        assertThat(FieldsController.looksLikeIdOrTime("valid")).isFalse();
        assertThat(FieldsController.looksLikeIdOrTime("notional")).isFalse();

        Set<String> strict = new HashSet<>();
        FieldsController.collectPaths(TestData.json("""
                {"and": [{"path": "desk", "op": "==", "value": "FX"}, {"or": [{"path": "trade.status", "op": "==", "value": "X"}]}]}
                """), strict);
        assertThat(strict).containsExactlyInAnyOrder("desk", "trade.status");

        assertThat(FieldsController.isSuggested(new FieldCatalog.KnownField("trade.status", "string", 3), Set.of())).isTrue();
        assertThat(FieldsController.isSuggested(new FieldCatalog.KnownField("trade.status", "string", 3), strict)).isFalse();
        assertThat(FieldsController.isSuggested(new FieldCatalog.KnownField("host", "string", 5000), Set.of())).isFalse();
    }
}
