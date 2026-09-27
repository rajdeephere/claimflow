package com.claimflow.policy.policy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Year;

/** POL-2026-000042. A DB sequence is atomic, so concurrent requests never get the same number. */
@Component
public class PolicyNumberGenerator {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public PolicyNumberGenerator(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public String next() {
        Long seq = jdbc.queryForObject("SELECT nextval('policy_number_seq')", Long.class);
        return "POL-%d-%06d".formatted(Year.now(clock).getValue(), seq);
    }
}
