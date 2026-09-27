package com.claimflow.common.error;

/**
 * The request is well-formed but breaks a business rule (expired policy, amount over coverage...).
 * Maps to 422 Unprocessable Entity.
 */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
