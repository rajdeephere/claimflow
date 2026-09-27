package com.claimflow.validation.messaging;

import com.claimflow.common.events.EventEnvelope;
import com.claimflow.common.events.EventTypes;
import com.claimflow.common.events.Topics;
import com.claimflow.common.events.payload.ClaimEvents.ClaimSubmitted;
import com.claimflow.common.kafka.KafkaHeaderNames;
import com.claimflow.validation.policy.PolicyServiceStub;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = {
        "claimflow.kafka.retry.initial-interval-ms=100",
        "claimflow.kafka.retry.dependency-initial-interval-ms=200",   // 200, 400, 800, 1600 ms in the outage test
        "claimflow.policy-service.read-timeout-ms=1000"
})
class ValidationKafkaIntegrationTest {

    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");
    static final PolicyServiceStub POLICY;

    static {
        KAFKA.start();
        try {
            POLICY = new PolicyServiceStub();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("claimflow.policy-service.base-url", POLICY::baseUrl);
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static KafkaConsumer<String, String> observer;
    private static final List<ConsumerRecord<String, String>> seen = new CopyOnWriteArrayList<>();

    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    ObjectMapper mapper;

    @BeforeAll
    static void startObserver() {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        observer = new KafkaConsumer<>(p);
        observer.subscribe(List.of(Topics.VALIDATION_EVENTS, Topics.dlt(Topics.CLAIM_EVENTS)));
    }

    @AfterAll
    static void stop() {
        observer.close();
        POLICY.close();
    }

    private List<ConsumerRecord<String, String>> awaitRecords(Predicate<ConsumerRecord<String, String>> match, int n) {
        return await().atMost(TIMEOUT).until(() -> {
            observer.poll(Duration.ofMillis(200)).forEach(seen::add);
            return seen.stream().filter(match).toList();
        }, list -> list.size() >= n);
    }

    private ConsumerRecord<String, String> awaitRecord(Predicate<ConsumerRecord<String, String>> match) {
        return awaitRecords(match, 1).get(0);
    }

    private static String header(ConsumerRecord<String, String> r, String name) {
        Header h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    private static Predicate<ConsumerRecord<String, String>> resultFor(UUID claimId) {
        return r -> r.topic().equals(Topics.VALIDATION_EVENTS) && claimId.toString().equals(r.key());
    }

    /** Sends a ClaimSubmitted as claim-service would; returns the envelope sent. */
    private EventEnvelope submit(ClaimSubmitted claim, UUID eventId, String correlationId) throws Exception {
        EventEnvelope env = new EventEnvelope(eventId, EventTypes.CLAIM_SUBMITTED, 1, Instant.now(), "claim-service",
                claim.claimId(), correlationId, mapper.valueToTree(claim));
        ProducerRecord<String, String> record = new ProducerRecord<>(Topics.CLAIM_EVENTS, claim.claimId().toString(),
                mapper.writeValueAsString(env));
        record.headers().add(KafkaHeaderNames.CORRELATION_ID, correlationId.getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get();
        return env;
    }

    private static ClaimSubmitted claim(UUID policyId, String amount) {
        return new ClaimSubmitted(UUID.randomUUID(), "CLM-2026-000001", policyId, "COLLISION",
                LocalDate.now().minusDays(1), new BigDecimal(amount));
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    void coveredClaimIsValidatedAndCorrelationIdFlowsBothWays() throws Exception {
        UUID policy = UUID.randomUUID();
        POLICY.respond(policy, PolicyServiceStub.covered(policy, "500000.00", "20000.00"));
        ClaimSubmitted c = claim(policy, "200000.00");

        submit(c, UUID.randomUUID(), "corr-val-1");

        ConsumerRecord<String, String> result = awaitRecord(resultFor(c.claimId()));
        assertThat(header(result, KafkaHeaderNames.EVENT_TYPE)).isEqualTo(EventTypes.CLAIM_VALIDATED);
        assertThat(header(result, KafkaHeaderNames.CORRELATION_ID)).isEqualTo("corr-val-1");   // Kafka -> Kafka
        assertThat(POLICY.correlationIds).contains("corr-val-1");                              // Kafka -> HTTP
        JsonNode payload = mapper.readTree(result.value()).get("payload");
        assertThat(payload.get("coverageLimit").decimalValue()).isEqualTo(new BigDecimal("500000.00"));
        assertThat(payload.get("warnings")).isEmpty();
    }

    @Test
    void uncoveredClaimFailsWithReason() throws Exception {
        UUID policy = UUID.randomUUID();
        POLICY.respond(policy, PolicyServiceStub.coverage(policy, false, "POLICY_CANCELLED", "500000", "20000"));
        ClaimSubmitted c = claim(policy, "200000.00");

        submit(c, UUID.randomUUID(), "corr-val-2");

        ConsumerRecord<String, String> result = awaitRecord(resultFor(c.claimId()));
        assertThat(header(result, KafkaHeaderNames.EVENT_TYPE)).isEqualTo(EventTypes.CLAIM_VALIDATION_FAILED);
        assertThat(mapper.readTree(result.value()).get("payload").get("reasons").get(0).asText()).contains("cancelled");
    }

    @Test
    void unknownPolicyFails() throws Exception {
        UUID policy = UUID.randomUUID();
        POLICY.respond(policy, PolicyServiceStub.notFound(policy));
        ClaimSubmitted c = claim(policy, "1000");

        submit(c, UUID.randomUUID(), "corr-val-3");

        ConsumerRecord<String, String> result = awaitRecord(resultFor(c.claimId()));
        assertThat(header(result, KafkaHeaderNames.EVENT_TYPE)).isEqualTo(EventTypes.CLAIM_VALIDATION_FAILED);
        assertThat(result.value()).contains("not found");
    }

    @Test
    void redeliveredClaimSubmittedProducesTheSameResultEventId() throws Exception {
        UUID policy = UUID.randomUUID();
        POLICY.respond(policy, PolicyServiceStub.covered(policy, "500000.00", "20000.00"));
        ClaimSubmitted c = claim(policy, "200000.00");
        UUID eventId = UUID.randomUUID();

        submit(c, eventId, "corr-dup");
        submit(c, eventId, "corr-dup");   // the same event again (redelivery / outbox re-send)

        List<ConsumerRecord<String, String>> results = awaitRecords(resultFor(c.claimId()), 2);
        assertThat(results).extracting(r -> header(r, KafkaHeaderNames.EVENT_ID)).containsOnly(
                ValidationResultPublisher.resultEventId(eventId).toString());   // claim-service will drop the 2nd
    }

    @Test
    void policyServiceOutageDelaysValidationInsteadOfDeadLettering() throws Exception {
        UUID policy = UUID.randomUUID();
        // four failures (503), then healthy. The default policy (3 retries) would dead-letter this claim;
        // the dependency backoff keeps retrying until Policy Service recovers.
        POLICY.respond(policy, PolicyServiceStub.serverError(), PolicyServiceStub.serverError(),
                PolicyServiceStub.serverError(), PolicyServiceStub.serverError(),
                PolicyServiceStub.covered(policy, "500000.00", "20000.00"));
        ClaimSubmitted c = claim(policy, "200000.00");

        submit(c, UUID.randomUUID(), "corr-outage");

        ConsumerRecord<String, String> result = awaitRecord(resultFor(c.claimId()));
        assertThat(header(result, KafkaHeaderNames.EVENT_TYPE)).isEqualTo(EventTypes.CLAIM_VALIDATED);
        assertThat(seen).noneMatch(r -> r.topic().equals(Topics.dlt(Topics.CLAIM_EVENTS))
                && c.claimId().toString().equals(r.key()));
    }

    @Test
    void malformedClaimEventGoesToDeadLetterTopic() throws Exception {
        UUID key = UUID.randomUUID();
        kafka.send(new ProducerRecord<>(Topics.CLAIM_EVENTS, key.toString(), "{oops")).get();

        ConsumerRecord<String, String> dead = awaitRecord(
                r -> r.topic().equals(Topics.dlt(Topics.CLAIM_EVENTS)) && key.toString().equals(r.key()));
        assertThat(header(dead, "kafka_dlt-exception-cause-fqcn")).contains("Json");
    }

    @Test
    void otherClaimEventsAreIgnored() throws Exception {
        UUID claimId = UUID.randomUUID();
        EventEnvelope approved = new EventEnvelope(UUID.randomUUID(), EventTypes.CLAIM_APPROVED, 1, Instant.now(),
                "claim-service", claimId, "corr-ignore", mapper.readTree("{}"));
        kafka.send(new ProducerRecord<>(Topics.CLAIM_EVENTS, claimId.toString(),
                mapper.writeValueAsString(approved))).get();

        // then a real ClaimSubmitted on the SAME key (same partition, processed after it)
        UUID policy = UUID.randomUUID();
        POLICY.respond(policy, PolicyServiceStub.covered(policy, "10.00", "1.00"));
        ClaimSubmitted c = new ClaimSubmitted(claimId, "CLM-X", policy, "COLLISION", LocalDate.now(), new BigDecimal("5"));
        submit(c, UUID.randomUUID(), "corr-ignore");

        List<ConsumerRecord<String, String>> results = awaitRecords(resultFor(claimId), 1);
        assertThat(results).hasSize(1);   // only the ClaimSubmitted produced a result
        assertThat(Optional.ofNullable(header(results.get(0), KafkaHeaderNames.EVENT_TYPE)))
                .contains(EventTypes.CLAIM_VALIDATED);
    }
}
