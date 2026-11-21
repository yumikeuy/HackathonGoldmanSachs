package com.hackathon.alerting.runbooks.repository;

/**
 * A runbook file to serve from {@code GET /api/runbooks/{id}/file}.
 *
 * @param fileName original file name; the {@code Content-Type} is derived from its extension
 * @param content  raw bytes as uploaded
 */
public record RunbookFile(String fileName, byte[] content) {
}
