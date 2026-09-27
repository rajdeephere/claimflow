package com.claimflow.claim.history;

import com.claimflow.claim.claim.ClaimStatus;
import com.claimflow.claim.claim.HistoryEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/**
 * One audit row per claim operation. {@code @Immutable}: Hibernate never issues an UPDATE for it,
 * and every column is updatable = false, so the trail is append-only from the application side.
 */
@Entity
@Immutable
@Table(name = "claim_history")
public class ClaimHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private UUID claimId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private HistoryEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    private ClaimStatus oldStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ClaimStatus newStatus;

    @Column(nullable = false, updatable = false)
    private String performedBy;

    @Column(updatable = false)
    private String correlationId;

    @Column(updatable = false)
    private String details;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected ClaimHistory() {
        // for JPA
    }

    public ClaimHistory(UUID claimId, HistoryEventType eventType, ClaimStatus oldStatus, ClaimStatus newStatus,
                        String performedBy, String correlationId, String details, Instant createdAt) {
        this.claimId = claimId;
        this.eventType = eventType;
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
        this.performedBy = performedBy;
        this.correlationId = correlationId;
        this.details = details;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getClaimId() {
        return claimId;
    }

    public HistoryEventType getEventType() {
        return eventType;
    }

    public ClaimStatus getOldStatus() {
        return oldStatus;
    }

    public ClaimStatus getNewStatus() {
        return newStatus;
    }

    public String getPerformedBy() {
        return performedBy;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getDetails() {
        return details;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
