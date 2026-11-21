package com.hackathon.alerting.rules.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** 409: the rule cannot be deleted because alerts reference it; deactivate it instead. */
public class RuleInUseException extends ErrorResponseException {

    public RuleInUseException(long ruleId) {
        super(HttpStatus.CONFLICT, ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Rule " + ruleId + " has alerts and cannot be deleted; set active = false instead"), null);
    }
}
