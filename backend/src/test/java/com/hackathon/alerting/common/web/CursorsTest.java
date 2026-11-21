package com.hackathon.alerting.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorsTest {

    @Test
    void roundTripsTypedKeysetValues() {
        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put("lastSeenAt", Instant.parse("2026-11-21T00:17:03.120Z"));
        keys.put("id", 42L);
        keys.put("eventId", UUID.randomUUID());
        keys.put("priority", 7);
        keys.put("name", "a:b");
        Window<String> window = Window.from(List.of("x"), i -> ScrollPosition.forward(keys), true);

        String cursor = Cursors.next(window);
        ScrollPosition decoded = Cursors.decode(cursor);

        assertThat(cursor).doesNotContain("=", "+", "/");
        assertThat(decoded).isInstanceOf(KeysetScrollPosition.class);
        assertThat(((KeysetScrollPosition) decoded).getKeys()).containsExactlyEntriesOf(keys);
    }

    @Test
    void lastPageHasNoCursorAndMissingCursorMeansFirstPage() {
        Window<String> last = Window.from(List.of("x"), i -> ScrollPosition.forward(Map.of("id", 1L)), false);

        assertThat(Cursors.next(last)).isNull();
        assertThat(Cursors.decode(null)).isEqualTo(ScrollPosition.keyset());
        assertThat(Cursors.decode(" ")).isEqualTo(ScrollPosition.keyset());
    }

    @Test
    void rejectsGarbageAsBadRequest() {
        assertThatThrownBy(() -> Cursors.decode("not-a-cursor")).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> Cursors.decode("e30")).isInstanceOf(InvalidRequestException.class); // "{}"
    }

    @Test
    void clampsLimit() {
        assertThat(Cursors.limit(null).max()).isEqualTo(CursorPage.DEFAULT_LIMIT);
        assertThat(Cursors.limit(0).max()).isEqualTo(1);
        assertThat(Cursors.limit(10_000).max()).isEqualTo(CursorPage.MAX_LIMIT);
    }

    @Test
    void pageMapsItemsAndCarriesCursor() {
        Window<Integer> window = Window.from(List.of(1, 2), i -> ScrollPosition.forward(Map.of("id", (long) i)), true);

        CursorPage<String> page = CursorPage.of(window, n -> "#" + n);

        assertThat(page.items()).containsExactly("#1", "#2");
        assertThat(((KeysetScrollPosition) Cursors.decode(page.nextCursor())).getKeys()).containsEntry("id", 1L);
    }
}
