package com.hackathon.alerting.rules.alert;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** 409: the requested transition is not possible because the alert is already resolved (e.g. ack after resolve). */
public class AlertAlreadyResolvedException extends ErrorResponseException {

    public AlertAlreadyResolvedException(long alertId) {
        super(HttpStatus.CONFLICT, ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Alert " + alertId + " is already resolved"), null);
    }
}
