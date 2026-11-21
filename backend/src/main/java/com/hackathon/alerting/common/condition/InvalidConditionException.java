package com.hackathon.alerting.common.condition;

import com.hackathon.alerting.common.web.InvalidRequestException;

/**
 * A condition tree is malformed: unknown operator, invalid regex, wrong value type, bad node shape.
 * Thrown by {@link ConditionCompiler#compile}; reaches the client as HTTP 400 with the message as {@code detail}.
 */
public class InvalidConditionException extends InvalidRequestException {

    public InvalidConditionException(String detail) {
        super(detail);
    }

    public InvalidConditionException(String detail, Throwable cause) {
        super(detail, cause);
    }
}
