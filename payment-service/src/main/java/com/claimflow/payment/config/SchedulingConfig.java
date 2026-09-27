package com.claimflow.payment.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Runs the outbox relay and the payment processor. Can be switched off (e.g. in a test that drives the relay by hand). */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "claimflow.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
