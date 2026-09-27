package com.claimflow.payment.gateway;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The bank / payment provider. Contract (like Stripe or Adyen): calling transfer() again with the same
 * idempotency key returns the ORIGINAL result and never moves money twice.
 */
public interface PaymentGateway {

    sealed interface Result permits Success, Declined {
    }

    /** Money moved. */
    record Success(String reference) implements Result {
    }

    /** The bank refused (business outcome, retrying won't help). */
    record Declined(String reason) implements Result {
    }

    /**
     * @throws GatewayUnavailableException the provider couldn't be reached or timed out; the
     *         outcome is unknown, so retry with the SAME idempotency key
     */
    Result transfer(UUID idempotencyKey, String claimNumber, BigDecimal amount);
}
