package com.hackathon.alerting.common.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * 503 when a dependency (Kafka) did not confirm in time; the client should retry later.
 * Rendered as RFC 9457 {@code application/problem+json}.
 */
public class ServiceUnavailableException extends ErrorResponseException {

    public ServiceUnavailableException(String detail, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, detail), cause);
    }
}
