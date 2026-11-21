package com.hackathon.alerting.common.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

import java.time.Duration;

/**
 * 429 when a caller exceeds its rate limit; carries {@code Retry-After}.
 * Rendered as RFC 9457 {@code application/problem+json}.
 */
public class TooManyRequestsException extends ErrorResponseException {

    public TooManyRequestsException(String detail, Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, detail), null);
        getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, retryAfter.toSeconds())));
    }
}
