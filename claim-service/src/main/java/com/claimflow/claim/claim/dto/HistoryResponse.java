package com.claimflow.claim.claim.dto;

import com.claimflow.claim.claim.ClaimStatus;
import com.claimflow.claim.claim.HistoryEventType;
import com.claimflow.claim.history.ClaimHistory;

import java.time.Instant;

public record HistoryResponse(
        HistoryEventType eventType,
        ClaimStatus oldStatus,
        ClaimStatus newStatus,
        String performedBy,
        String correlationId,
        String details,
        Instant timestamp) {

    public static HistoryResponse from(ClaimHistory h) {
        return new HistoryResponse(h.getEventType(), h.getOldStatus(), h.getNewStatus(), h.getPerformedBy(),
                h.getCorrelationId(), h.getDetails(), h.getCreatedAt());
    }
}
