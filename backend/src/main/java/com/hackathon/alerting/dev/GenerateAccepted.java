package com.hackathon.alerting.dev;

/**
 * Response of {@code POST /api/dev/generate} ({@code GenerateAccepted} in {@code frontend/openapi.yaml}).
 *
 * @param count how many events this run will send
 */
public record GenerateAccepted(int count) {
}
