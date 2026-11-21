package com.hackathon.alerting.common.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * 413 for a request body above the configured limit (e.g. an event larger than {@code alerting.ingest.max-body-size}).
 * Rendered as RFC 9457 {@code application/problem+json}.
 */
public class PayloadTooLargeException extends ErrorResponseException {

    public PayloadTooLargeException(String detail) {
        super(HttpStatus.PAYLOAD_TOO_LARGE, ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, detail), null);
    }
}
