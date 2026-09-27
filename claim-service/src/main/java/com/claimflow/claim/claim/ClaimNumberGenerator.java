package com.claimflow.claim.claim;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Year;

/** CLM-2026-000042, from an atomic DB sequence. */
@Component
public class ClaimNumberGenerator {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ClaimNumberGenerator(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public String next() {
        Long seq = jdbc.queryForObject("SELECT nextval('claim_number_seq')", Long.class);
        return "CLM-%d-%06d".formatted(Year.now(clock).getValue(), seq);
    }
}
