package com.hackathon.alerting.common.web;

import org.springframework.core.MethodParameter;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Serves uploaded runbook files ({@code GET /api/runbooks/{id}/file}) so that a malicious upload cannot run script in
 * the operator's browser (stored XSS), while still accepting files of <b>any</b> format (AGENTS.md §2.6).
 *
 * <ul>
 *   <li>Only passive types are shown inline: PDF, plain text, Markdown, images except SVG. Everything else — HTML, SVG,
 *       XML, JavaScript, unknown — is sent as {@code Content-Disposition: attachment} (downloaded, never rendered
 *       on our origin).</li>
 *   <li>A file that claims a passive text type but whose content starts like markup ({@code <html}, {@code <script},
 *       {@code <svg}, …) is treated as active: attachment.</li>
 *   <li>Every file response gets {@code Content-Security-Policy: sandbox; default-src 'none'} — even if a browser
 *       renders it, scripts, forms and plugins are disabled — on top of {@code nosniff} from
 *       {@link SecurityHeadersFilter}.</li>
 * </ul>
 * Implemented as a response advice so the runbooks module (Role 3) needs no change; it only rewrites headers.
 */
@ControllerAdvice
public class SafeFileDownloadAdvice implements ResponseBodyAdvice<Object> {

    static final Pattern RUNBOOK_FILE = Pattern.compile("^/api/runbooks/[^/]+/file$");
    static final String FILE_CSP = "sandbox; default-src 'none'; style-src 'unsafe-inline'; img-src data:";

    /** Types that browsers display without running anything. */
    static final Set<String> INLINE_SAFE = Set.of(
            "application/pdf", "text/plain", "text/markdown", "text/csv",
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp");

    private static final Pattern MARKUP_START = Pattern.compile(
            "^\\s*(<!doctype\\s+html|<html|<script|<svg|<\\?xml|<iframe|<body|<head|<object|<embed)", Pattern.CASE_INSENSITIVE);
    private static final int SNIFF_BYTES = 512;

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (!RUNBOOK_FILE.matcher(request.getURI().getPath()).matches() || !(body instanceof byte[] content)) {
            return body;
        }
        HttpHeaders headers = response.getHeaders();
        headers.set("Content-Security-Policy", FILE_CSP);
        if (!isInlineSafe(selectedContentType, content)) {
            String fileName = fileName(headers);
            ContentDisposition.Builder attachment = ContentDisposition.attachment();
            if (fileName != null) {
                attachment.filename(fileName, StandardCharsets.UTF_8);
            }
            headers.setContentDisposition(attachment.build());
        }
        return body;
    }

    static boolean isInlineSafe(MediaType type, byte[] content) {
        if (type == null) {
            return false;
        }
        String base = (type.getType() + "/" + type.getSubtype()).toLowerCase(Locale.ROOT);
        if (!INLINE_SAFE.contains(base)) {
            return false;
        }
        return !base.startsWith("text/") || !looksLikeMarkup(content);
    }

    static boolean looksLikeMarkup(byte[] content) {
        String head = new String(content, 0, Math.min(content.length, SNIFF_BYTES), StandardCharsets.UTF_8);
        if (!head.isEmpty() && head.charAt(0) == '﻿') {
            head = head.substring(1);
        }
        return MARKUP_START.matcher(head).find();
    }

    private static String fileName(HttpHeaders headers) {
        String value = headers.getFirst(HttpHeaders.CONTENT_DISPOSITION);
        if (value == null) {
            return null;
        }
        try {
            return ContentDisposition.parse(value).getFilename();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
