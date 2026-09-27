package com.claimflow.claim.messaging;

import com.claimflow.claim.AbstractIntegrationTest;
import com.claimflow.claim.outbox.OutboxRepository;
import com.claimflow.common.events.EventEnvelope;
import com.claimflow.common.events.EventTypes;
import com.claimflow.common.events.Topics;
import com.claimflow.common.events.payload.ClaimEvents;
import com.claimflow.common.kafka.KafkaHeaderNames;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The event-driven path end to end: HTTP -> outbox -> Kafka, and Kafka -> consumer -> state change,
 * with duplicates, poison messages and correlation-ID propagation.
 */
class ClaimKafkaIntegrationTest extends AbstractIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Autowired
    TestRestTemplate http;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    OutboxRepository outbox;

    // A plain Kafka consumer (its own group) that records everything claim-service publishes or dead-letters.
    private static KafkaConsumer<String, String> observer;
    private static final List<ConsumerRecord<String, String>> seen = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startObserver() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-observer-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        observer = new KafkaConsumer<>(props);
        observer.subscribe(List.of(Topics.CLAIM_EVENTS, Topics.dlt(Topics.VALIDATION_EVENTS),
                Topics.dlt(Topics.PAYMENT_EVENTS)));
    }

    @AfterAll
    static void stopObserver() {
        observer.close();
    }

    /** Polls until a record matching the predicate has been seen. */
    private ConsumerRecord<String, String> awaitRecord(Predicate<ConsumerRecord<String, String>> match) {
        return await().atMost(TIMEOUT).until(() -> {
            observer.poll(Duration.ofMillis(200)).forEach(seen::add);
            return seen.stream().filter(match).findFirst();
        }, Optional::isPresent).orElseThrow();
    }

    private static String header(ConsumerRecord<String, String> r, String name) {
        Header h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    private static Predicate<ConsumerRecord<String, String>> event(String topic, String type, UUID claimId) {
        return r -> r.topic().equals(topic) && type.equals(header(r, KafkaHeaderNames.EVENT_TYPE))
                && claimId.toString().equals(r.key());
    }

    private UUID fileFnol(String correlationId) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Correlation-ID", correlationId);
        h.set("X-User-Id", "agent-7");
        JsonNode body = http.exchange("/api/v1/claims", HttpMethod.POST, new HttpEntity<>(Map.of(
                "policyId", UUID.randomUUID(), "lossType", "COLLISION", "incidentDate", "2026-03-10",
                "description", "Rear-ended", "claimedAmount", "200000.00"), h), JsonNode.class).getBody();
        return UUID.fromString(body.get("id").asText());
    }

    /** Publishes an event as if another service had sent it; returns its eventId. */
    private UUID send(String topic, String type, UUID claimId, Object payload, String correlationId) throws Exception {
        UUID eventId = UUID.randomUUID();
        sendWithId(topic, type, claimId, payload, correlationId, eventId);
        return eventId;
    }

    private void sendWithId(String topic, String type, UUID claimId, Object payload, String correlationId,
                            UUID eventId) throws Exception {
        EventEnvelope env = new EventEnvelope(eventId, type, 1, Instant.now(), "test", claimId, correlationId,
                mapper.valueToTree(payload));
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, claimId.toString(),
                mapper.writeValueAsString(env));
        record.headers().add(KafkaHeaderNames.CORRELATION_ID, correlationId.getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get();
    }

    private String status(UUID claimId) {
        return http.getForObject("/api/v1/claims/" + claimId, JsonNode.class).get("status").asText();
    }

    private long historyCount(UUID claimId, String eventType) {
        JsonNode history = http.getForObject("/api/v1/claims/" + claimId + "/history", JsonNode.class);
        long n = 0;
        for (JsonNode h : history) {
            if (h.get("eventType").asText().equals(eventType)) n++;
        }
        return n;
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    void fnolIsPublishedThroughTheOutboxWithCorrelationId() throws Exception {
        UUID claimId = fileFnol("corr-outbox-1");

        ConsumerRecord<String, String> record = awaitRecord(event(Topics.CLAIM_EVENTS, EventTypes.CLAIM_SUBMITTED, claimId));

        assertThat(header(record, KafkaHeaderNames.CORRELATION_ID)).isEqualTo("corr-outbox-1");
        EventEnvelope env = mapper.readValue(record.value(), EventEnvelope.class);
        assertThat(env.source()).isEqualTo("claim-service");
        // exact value AND scale (equals, not compareTo), and plain notation on the wire (BUG-009)
        assertThat(env.payload().get("claimedAmount").decimalValue()).isEqualTo(new BigDecimal("200000.00"));
        assertThat(record.value()).contains("\"claimedAmount\":200000.00");
        assertThat(env.payload().get("lossType").asText()).isEqualTo("COLLISION");
        await().atMost(TIMEOUT).until(() -> jdbc.queryForObject(
                "SELECT published_at IS NOT NULL FROM outbox_events WHERE id = ?", Boolean.class, env.eventId()));
    }

    @Test
    void validationEventMovesClaimAndDuplicateIsIgnored() throws Exception {
        UUID claimId = fileFnol("corr-dup");
        UUID eventId = UUID.randomUUID();
        ClaimEvents.ClaimValidated payload = new ClaimEvents.ClaimValidated(claimId, new BigDecimal("500000"),
                new BigDecimal("20000"), List.of());

        sendWithId(Topics.VALIDATION_EVENTS, EventTypes.CLAIM_VALIDATED, claimId, payload, "corr-dup", eventId);
        await().atMost(TIMEOUT).until(() -> status(claimId).equals("UNDER_REVIEW"));

        // Kafka redelivery / producer retry: the SAME event arrives again
        sendWithId(Topics.VALIDATION_EVENTS, EventTypes.CLAIM_VALIDATED, claimId, payload, "corr-dup", eventId);
        // and a marker event after it on the same partition (same key), so we know the duplicate was consumed
        UUID markerId = send(Topics.VALIDATION_EVENTS, "MarkerEvent", claimId, Map.of(), "corr-dup");
        await().atMost(TIMEOUT).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class, markerId) == 1);

        assertThat(historyCount(claimId, "CLAIM_VALIDATED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?",
                Integer.class, eventId)).isEqualTo(1);
        assertThat(status(claimId)).isEqualTo("UNDER_REVIEW");
    }

    @Test
    void validationFailureRejectsAndPublishesClaimRejectedWithSameCorrelationId() throws Exception {
        UUID claimId = fileFnol("corr-http");

        send(Topics.VALIDATION_EVENTS, EventTypes.CLAIM_VALIDATION_FAILED, claimId,
                new ClaimEvents.ClaimValidationFailed(claimId, List.of("Policy cancelled")), "corr-chain-7");

        ConsumerRecord<String, String> rejected =
                awaitRecord(event(Topics.CLAIM_EVENTS, EventTypes.CLAIM_REJECTED, claimId));
        // the correlation ID of the event that CAUSED the rejection flows onto the event it produced
        assertThat(header(rejected, KafkaHeaderNames.CORRELATION_ID)).isEqualTo("corr-chain-7");
        assertThat(status(claimId)).isEqualTo("REJECTED");
    }

    @Test
    void invalidTransitionGoesStraightToDeadLetterTopic() throws Exception {
        UUID claimId = fileFnol("corr-dlt");   // SUBMITTED

        // PaymentCompleted for a claim that was never approved: can never succeed, so no retries
        UUID eventId = send(Topics.PAYMENT_EVENTS, EventTypes.PAYMENT_COMPLETED, claimId,
                new ClaimEvents.PaymentCompleted(claimId, UUID.randomUUID(), BigDecimal.TEN), "corr-dlt");

        ConsumerRecord<String, String> dead = awaitRecord(
                r -> r.topic().equals(Topics.dlt(Topics.PAYMENT_EVENTS)) && claimId.toString().equals(r.key()));
        // Spring wraps listener errors in ListenerExecutionFailedException; the real cause has its own header
        assertThat(header(dead, "kafka_dlt-exception-cause-fqcn")).endsWith("InvalidStateTransitionException");
        assertThat(header(dead, "kafka_dlt-exception-message")).contains("cannot move from SUBMITTED to SETTLED");
        assertThat(status(claimId)).isEqualTo("SUBMITTED");
        // the failed transaction rolled back, so the event is NOT marked processed (could be replayed after a fix)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?",
                Integer.class, eventId)).isZero();
        // and it was not retried: the DLT got it on the first failure
        assertThat(header(dead, "kafka_dlt-original-offset")).isNotNull();
    }

    @Test
    void malformedMessageGoesToDeadLetterTopicAndConsumerKeepsWorking() throws Exception {
        UUID claimId = fileFnol("corr-poison");
        kafka.send(new ProducerRecord<>(Topics.VALIDATION_EVENTS, claimId.toString(), "{not json")).get();

        awaitRecord(r -> r.topic().equals(Topics.dlt(Topics.VALIDATION_EVENTS)) && claimId.toString().equals(r.key()));

        // the poison message didn't block the partition: a valid event for the same claim still gets processed
        send(Topics.VALIDATION_EVENTS, EventTypes.CLAIM_VALIDATED, claimId,
                new ClaimEvents.ClaimValidated(claimId, BigDecimal.TEN, BigDecimal.ONE, List.of()), "corr-poison");
        await().atMost(TIMEOUT).until(() -> status(claimId).equals("UNDER_REVIEW"));
    }

    @Test
    void fullEventDrivenLifecycleToSettled() throws Exception {
        UUID claimId = fileFnol("corr-full");
        send(Topics.VALIDATION_EVENTS, EventTypes.CLAIM_VALIDATED, claimId,
                new ClaimEvents.ClaimValidated(claimId, new BigDecimal("500000"), new BigDecimal("20000"), List.of()), "corr-full");
        await().atMost(TIMEOUT).until(() -> status(claimId).equals("UNDER_REVIEW"));

        // adjuster approves over REST -> ClaimApproved is published for Payment
        String adjusterId = http.postForEntity("/api/v1/adjusters",
                Map.of("name", "Ravi", "email", "ravi-" + UUID.randomUUID() + "@example.com"), JsonNode.class)
                .getBody().get("id").asText();
        http.postForEntity("/api/v1/claims/" + claimId + "/assign-adjuster", Map.of("adjusterId", adjusterId),
                JsonNode.class);
        http.exchange("/api/v1/claims/" + claimId + "/status", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("targetStatus", "APPROVED", "approvedAmount", "180000.00")), JsonNode.class);

        ConsumerRecord<String, String> approved =
                awaitRecord(event(Topics.CLAIM_EVENTS, EventTypes.CLAIM_APPROVED, claimId));
        JsonNode approvedPayload = mapper.readTree(approved.value()).get("payload");
        assertThat(approvedPayload.get("approvedAmount").decimalValue()).isEqualTo(new BigDecimal("180000.00"));
        // coverage terms from ClaimValidated are carried to Payment (event-carried state transfer)
        assertThat(approvedPayload.get("coverageLimit").decimalValue()).isEqualByComparingTo("500000");
        assertThat(approvedPayload.get("deductible").decimalValue()).isEqualByComparingTo("20000");
        assertThat(status(claimId)).isEqualTo("SETTLEMENT_PENDING");

        // Payment Service's events (simulated until Phase 6)
        UUID paymentId = UUID.randomUUID();
        send(Topics.PAYMENT_EVENTS, EventTypes.PAYMENT_INITIATED, claimId,
                new ClaimEvents.PaymentInitiated(claimId, paymentId, new BigDecimal("160000.00")), "corr-full");
        send(Topics.PAYMENT_EVENTS, EventTypes.PAYMENT_COMPLETED, claimId,
                new ClaimEvents.PaymentCompleted(claimId, paymentId, new BigDecimal("160000.00")), "corr-full");

        await().atMost(TIMEOUT).until(() -> status(claimId).equals("SETTLED"));
        assertThat(historyCount(claimId, "PAYMENT_COMPLETED")).isEqualTo(1);
    }

    @Test
    void outboxDrainsCompletely() {
        await().atMost(TIMEOUT).until(() -> outbox.countByPublishedAtIsNull() == 0);
    }
}
