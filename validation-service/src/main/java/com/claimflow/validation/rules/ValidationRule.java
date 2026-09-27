package com.claimflow.validation.rules;

/**
 * One business rule (Strategy pattern). Every {@code @Component} implementing this is picked up by
 * {@link com.claimflow.validation.ClaimValidator} automatically: adding a rule means adding a class,
 * with no change to the engine (open/closed principle). Order is set with {@code @Order}.
 */
public interface ValidationRule {

    RuleResult evaluate(ValidationContext context);
}
