package com.hackathon.alerting.common.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * 409 for a request that conflicts with existing data: duplicate unique name/email, a row that is still
 * referenced, an invalid state transition. Rendered as RFC 9457 {@code application/problem+json}.
 */
public class ConflictException extends ErrorResponseException {

    public ConflictException(String detail) {
        this(detail, null);
    }

    public ConflictException(String detail, Throwable cause) {
        super(HttpStatus.CONFLICT, ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail), cause);
    }
}
