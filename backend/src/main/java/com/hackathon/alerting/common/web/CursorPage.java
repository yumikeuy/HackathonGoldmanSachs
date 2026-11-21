package com.hackathon.alerting.common.web;

import org.springframework.data.domain.Window;

import java.util.List;
import java.util.function.Function;

/**
 * One page of a keyset-paginated list — the response shape of every list endpoint
 * ({@code frontend/openapi.yaml}, schema {@code *Page}).
 *
 * @param items      page content
 * @param nextCursor opaque cursor for the next page ({@code ?cursor=}), {@code null} on the last page
 * @param <T>        item type
 */
public record CursorPage<T>(List<T> items, String nextCursor) {

    /** Default and maximum page sizes (AGENTS.md §6: keyset pages of 50). */
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    public CursorPage {
        items = List.copyOf(items);
    }

    /** Converts a Spring Data keyset {@link Window} into a response page, mapping entities to DTOs. */
    public static <E, T> CursorPage<T> of(Window<E> window, Function<? super E, ? extends T> mapper) {
        List<T> items = window.getContent().stream().<T>map(mapper).toList();
        return new CursorPage<>(items, Cursors.next(window));
    }
}
