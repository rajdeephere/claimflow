package com.claimflow.payment.outbox;

import com.claimflow.common.kafka.KafkaHeaderNames;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Publishes outbox rows to Kafka, oldest first, and marks them published.
 *
 * Delivery is at-least-once: if the process dies after Kafka acknowledged a send but before the
 * published_at update commits, that event is sent again on restart. Consumers dedupe by eventId.
 * On a send failure the batch stops, so later events never overtake an earlier one.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final int batchSize;
    private final long sendTimeoutMs;

    public OutboxRelay(OutboxRepository outbox, KafkaTemplate<String, String> kafka,
                       PlatformTransactionManager txManager, Clock clock,
                       @Value("${claimflow.outbox.batch-size:100}") int batchSize,
                       @Value("${claimflow.outbox.send-timeout-ms:5000}") long sendTimeoutMs) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.batchSize = batchSize;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    @Scheduled(fixedDelayString = "${claimflow.outbox.poll-interval-ms:500}")
    public void publishPending() {
        // Keep going while full batches come back, so a backlog drains without waiting for the next tick.
        Integer published;
        do {
            published = tx.execute(status -> publishBatch());
        } while (published != null && published == batchSize);
    }

    private int publishBatch() {
        List<OutboxEvent> batch = outbox.lockNextBatch(batchSize);
        int published = 0;
        for (OutboxEvent event : batch) {
            try {
                kafka.send(toRecord(event)).get(sendTimeoutMs, TimeUnit.MILLISECONDS);   // wait for the broker ack
                event.markPublished(Instant.now(clock));
                published++;
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                event.recordFailure(e.toString());
                log.warn("Outbox publish failed for {} {} (attempt {}); will retry: {}", event.getEventType(),
                        event.getId(), event.getAttempts(), e.toString());
                break;   // preserve order: don't publish later events before this one
            }
        }
        if (published > 0) {
            log.debug("Published {} outbox event(s)", published);
        }
        return published;
    }

    private static ProducerRecord<String, String> toRecord(OutboxEvent event) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(event.getTopic(), event.getMessageKey(), event.getPayload());
        if (event.getCorrelationId() != null) {
            record.headers().add(KafkaHeaderNames.CORRELATION_ID, bytes(event.getCorrelationId()));
        }
        record.headers().add(KafkaHeaderNames.EVENT_TYPE, bytes(event.getEventType()));
        record.headers().add(KafkaHeaderNames.EVENT_ID, bytes(event.getId().toString()));
        return record;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
