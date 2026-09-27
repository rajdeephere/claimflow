package com.claimflow.validation.rules;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Policies usually require prompt notice of a loss. A late report isn't automatically rejected, but
 * it's a classic fraud and dispute indicator, so the adjuster gets a warning.
 */
@Component
@Order(50)
public class LateNotificationRule implements ValidationRule {

    private final long maxDays;

    public LateNotificationRule(@Value("${claimflow.validation.late-notification-days:30}") long maxDays) {
        this.maxDays = maxDays;
    }

    @Override
    public RuleResult evaluate(ValidationContext ctx) {
        LocalDate reported = ctx.reportedAt().atZone(ZoneOffset.UTC).toLocalDate();
        long days = ChronoUnit.DAYS.between(ctx.claim().incidentDate(), reported);
        if (days > maxDays) {
            return RuleResult.warn("Loss reported " + days + " days after the incident (limit " + maxDays + ")");
        }
        return RuleResult.pass();
    }
}
