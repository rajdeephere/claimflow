package com.claimflow.claim.claim.dto;

import com.claimflow.claim.claim.Claim;
import com.claimflow.claim.claim.ClaimStatus;
import com.claimflow.claim.claim.LossType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

public record ClaimResponse(
        UUID id,
        String claimNumber,
        UUID policyId,
        LossType lossType,
        LocalDate incidentDate,
        Instant reportedAt,
        String description,
        BigDecimal claimedAmount,
        BigDecimal approvedAmount,
        BigDecimal coverageLimit,
        BigDecimal deductible,
        ClaimStatus status,
        Set<ClaimStatus> allowedNextStatuses,   // lets a UI show only valid actions
        UUID adjusterId,
        String rejectionReason,
        Long version,
        Instant createdAt,
        Instant updatedAt) {

    public static ClaimResponse from(Claim c) {
        return new ClaimResponse(c.getId(), c.getClaimNumber(), c.getPolicyId(), c.getLossType(), c.getIncidentDate(),
                c.getReportedAt(), c.getDescription(), c.getClaimedAmount(), c.getApprovedAmount(),
                c.getCoverageLimit(), c.getDeductible(), c.getStatus(), c.getStatus().allowedNext(), c.getAdjusterId(), c.getRejectionReason(), c.getVersion(),
                c.getCreatedAt(), c.getUpdatedAt());
    }
}
