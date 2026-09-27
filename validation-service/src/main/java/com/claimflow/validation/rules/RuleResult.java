package com.claimflow.validation.rules;

/** Outcome of one rule: PASS, FAIL (claim rejected) or WARN (claim proceeds, adjuster is told). */
public record RuleResult(Outcome outcome, String message) {

    public enum Outcome {
        PASS,
        FAIL,
        WARN
    }

    private static final RuleResult PASS = new RuleResult(Outcome.PASS, null);

    public static RuleResult pass() {
        return PASS;
    }

    public static RuleResult fail(String message) {
        return new RuleResult(Outcome.FAIL, message);
    }

    public static RuleResult warn(String message) {
        return new RuleResult(Outcome.WARN, message);
    }
}
