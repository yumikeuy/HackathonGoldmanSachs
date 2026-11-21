package com.hackathon.alerting.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SafeFileDownloadAdviceTest {

    @Test
    void passiveTypesAreShownInline() {
        assertThat(inline("application/pdf", "%PDF-1.7")).isTrue();
        assertThat(inline("text/plain", "1. Restart the workflow\n2. Check the curve")).isTrue();
        assertThat(inline("text/markdown", "# Trade rejected\n- call the desk")).isTrue();
        assertThat(inline("image/png", "\u0089PNG")).isTrue();
    }

    @Test
    void activeAndUnknownTypesAreDownloaded() {
        assertThat(inline("text/html", "<p>hi</p>")).isFalse();
        assertThat(inline("image/svg+xml", "<svg onload=alert(1)>")).isFalse();
        assertThat(inline("application/xml", "<a/>")).isFalse();
        assertThat(inline("application/javascript", "alert(1)")).isFalse();
        assertThat(inline("application/octet-stream", "whatever")).isFalse();
        assertThat(SafeFileDownloadAdvice.isInlineSafe(null, new byte[0])).isFalse();
    }

    @Test
    void markupDisguisedAsTextIsDownloaded() {
        assertThat(inline("text/plain", "  <!DOCTYPE html><script>alert(1)</script>")).isFalse();
        assertThat(inline("text/plain", "﻿<script>steal()</script>")).isFalse();
        assertThat(inline("text/markdown", "<svg/onload=alert(1)>")).isFalse();
        assertThat(inline("text/plain", "use <b>bold</b> in the ticket")).isTrue();
    }

    private static boolean inline(String type, String content) {
        return SafeFileDownloadAdvice.isInlineSafe(MediaType.parseMediaType(type), content.getBytes(StandardCharsets.UTF_8));
    }
}
