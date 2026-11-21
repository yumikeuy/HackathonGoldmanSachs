package com.hackathon.alerting.common.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * 401 for a request without valid credentials (e.g. a missing or wrong source API key).
 * Rendered as RFC 9457 {@code application/problem+json}.
 */
public class UnauthorizedException extends ErrorResponseException {

    public UnauthorizedException(String detail) {
        super(HttpStatus.UNAUTHORIZED, ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, detail), null);
    }
}
