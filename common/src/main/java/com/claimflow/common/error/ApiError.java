package com.claimflow.common.error;

import java.time.Instant;
import java.util.List;

/** Uniform error body returned by every service. */
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        String correlationId,
        List<FieldViolation> violations) {

    public record FieldViolation(String field, String message) {
    }
}
