package com.hackathon.alerting.common.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * 400 for a request the client must fix (invalid condition, bad cursor, inconsistent fields).
 * Rendered as RFC 9457 {@code application/problem+json}; {@code detail} is shown to the user as is,
 * so make it specific: {@code "Unknown operator 'equals' at and[1]"}.
 */
public class InvalidRequestException extends ErrorResponseException {

    public InvalidRequestException(String detail) {
        this(detail, null);
    }

    public InvalidRequestException(String detail, Throwable cause) {
        super(HttpStatus.BAD_REQUEST, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail), cause);
    }
}
