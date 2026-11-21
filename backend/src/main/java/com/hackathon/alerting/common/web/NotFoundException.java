package com.hackathon.alerting.common.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * 404 for a missing resource. Rendered as RFC 9457 {@code application/problem+json}
 * by Spring MVC ({@code spring.mvc.problemdetails.enabled}).
 *
 * <pre>{@code throw new NotFoundException("Rule", id);}</pre>
 */
public class NotFoundException extends ErrorResponseException {

    public NotFoundException(String resource, Object id) {
        super(HttpStatus.NOT_FOUND, ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, resource + " " + id + " not found"), null);
    }
}
