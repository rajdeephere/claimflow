package com.claimflow.payment.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stand-in for a real bank API (no real gateway in scope). It behaves like one where it matters:
 * <ul>
 *   <li>idempotency: the same key returns the stored result, and money moves once
 *       ({@link #transfersExecuted()} counts actual transfers)</li>
 *   <li>declines above a configured amount (a bank transfer limit)</li>
 *   <li>takes some time</li>
 * </ul>
 * The idempotency store is in memory; a real provider keeps it on its side.
 */
@Component
public class SimulatedPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentGateway.class);

    private final Map<UUID, Result> byIdempotencyKey = new ConcurrentHashMap<>();
    private final AtomicInteger transfersExecuted = new AtomicInteger();
    private final BigDecimal declineAbove;
    private final long latencyMs;

    public SimulatedPaymentGateway(@Value("${claimflow.payment.gateway.decline-above:1000000.00}") BigDecimal declineAbove,
                                   @Value("${claimflow.payment.gateway.latency-ms:200}") long latencyMs) {
        this.declineAbove = declineAbove;
        this.latencyMs = latencyMs;
    }

    @Override
    public Result transfer(UUID idempotencyKey, String claimNumber, BigDecimal amount) {
        return byIdempotencyKey.computeIfAbsent(idempotencyKey, key -> {
            sleep();
            if (amount.compareTo(declineAbove) > 0) {
                log.info("Bank DECLINED {} for {} (limit {})", amount, claimNumber, declineAbove);
                return new Declined("Amount " + amount + " exceeds the single-transfer limit " + declineAbove);
            }
            transfersExecuted.incrementAndGet();
            String reference = "TRF-" + key.toString().substring(0, 8).toUpperCase();
            log.info("Bank transferred {} for {} (ref {})", amount, claimNumber, reference);
            return new Success(reference);
        });
    }

    public int transfersExecuted() {
        return transfersExecuted.get();
    }

    private void sleep() {
        try {
            Thread.sleep(latencyMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayUnavailableException("Interrupted while calling the bank");
        }
    }
}
