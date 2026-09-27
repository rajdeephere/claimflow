package com.claimflow.payment.messaging;

import com.claimflow.common.events.EventEnvelope;
import com.claimflow.common.events.EventTypes;
import com.claimflow.common.events.Topics;
import com.claimflow.common.events.payload.ClaimEvents.ClaimApproved;
import com.claimflow.common.kafka.KafkaHeaderNames;
import com.claimflow.payment.gateway.SimulatedPaymentGateway;
import com.claimflow.payment.payment.Payment;
import com.claimflow.payment.payment.PaymentRepository;
import com.claimflow.payment.settlement.Settlement;
import com.claimflow.payment.settlement.SettlementCalculator;
import com.claimflow.payment.settlement.SettlementRepository;
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
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "claimflow.kafka.retry.initial-interval-ms=100",
        "claimflow.outbox.poll-interval-ms=100",
        "claimflow.payment.processor.poll-interval-ms=200",
        "claimflow.payment.gateway.latency-ms=0",
        "claimflow.payment.gateway.decline-above=1000000.00"
})
class PaymentKafkaIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static KafkaConsumer<String, String> observer;
    private static final List<ConsumerRecord<String, String>> seen = new CopyOnWriteArrayList<>();

    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    TestRestTemplate http;
    @Autowired
    PaymentRepository payments;
    @Autowired
    SettlementRepository settlements;
    @Autowired
    SimulatedPaymentGateway gateway;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeAll
    static void startObserver() {
        var p = new java.util.Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        observer = new KafkaConsumer<>(p);
        observer.subscribe(List.of(Topics.PAYMENT_EVENTS, Topics.dlt(Topics.CLAIM_EVENTS)));
    }

    @AfterAll
    static void stop() {
        observer.close();
    }

    private List<ConsumerRecord<String, String>> awaitRecords(Predicate<ConsumerRecord<String, String>> match, int n) {
        return await().atMost(TIMEOUT).until(() -> {
            observer.poll(Duration.ofMillis(200)).forEach(seen::add);
            return seen.stream().filter(match).toList();
        }, list -> list.size() >= n);
    }

    private static String header(ConsumerRecord<String, String> r, String name) {
        Header h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    private static Predicate<ConsumerRecord<String, String>> paymentEvent(UUID claimId, String type) {
        return r -> r.topic().equals(Topics.PAYMENT_EVENTS) && claimId.toString().equals(r.key())
                && type.equals(header(r, KafkaHeaderNames.EVENT_TYPE));
    }

    private static ClaimApproved approved(String approved, String limit, String deductible) {
        return new ClaimApproved(UUID.randomUUID(), "CLM-2026-000077", UUID.randomUUID(), "COLLISION",
                LocalDate.of(2026, 3, 10), new BigDecimal(approved), new BigDecimal(approved),
                limit == null ? null : new BigDecimal(limit), deductible == null ? null : new BigDecimal(deductible));
    }

    private void send(ClaimApproved payload, UUID eventId, String correlationId) throws Exception {
        EventEnvelope env = new EventEnvelope(eventId, EventTypes.CLAIM_APPROVED, 1, Instant.now(), "claim-service",
                payload.claimId(), correlationId, mapper.valueToTree(payload));
        ProducerRecord<String, String> record = new ProducerRecord<>(Topics.CLAIM_EVENTS,
                payload.claimId().toString(), mapper.writeValueAsString(env));
        record.headers().add(KafkaHeaderNames.CORRELATION_ID, correlationId.getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get();
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    void approvedClaimIsSettledPaidAndAnnouncedInOrder() throws Exception {
        ClaimApproved claim = approved("200000.00", "500000.00", "20000.00");

        send(claim, UUID.randomUUID(), "corr-pay-1");

        ConsumerRecord<String, String> completed =
                awaitRecords(paymentEvent(claim.claimId(), EventTypes.PAYMENT_COMPLETED), 1).get(0);
        ConsumerRecord<String, String> initiated =
                awaitRecords(paymentEvent(claim.claimId(), EventTypes.PAYMENT_INITIATED), 1).get(0);
        // same key -> same partition; Initiated was published first
        assertThat(initiated.partition()).isEqualTo(completed.partition());
        assertThat(initiated.offset()).isLessThan(completed.offset());
        // the processor restored the approval's correlation ID for the event it published later
        assertThat(header(completed, KafkaHeaderNames.CORRELATION_ID)).isEqualTo("corr-pay-1");
        JsonNode payload = mapper.readTree(completed.value()).get("payload");
        assertThat(payload.get("amount").decimalValue()).isEqualTo(new BigDecimal("180000.00"));

        // REST view with the settlement breakdown
        JsonNode view = http.getForObject("/api/v1/payments?claimId=" + claim.claimId(), JsonNode.class);
        assertThat(view.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(view.get("gatewayReference").asText()).startsWith("TRF-");
        assertThat(view.get("settlement").get("payableAmount").decimalValue()).isEqualTo(new BigDecimal("180000.00"));
        assertThat(view.get("settlement").get("cappedAtLimit").asBoolean()).isFalse();
    }

    @Test
    void sameEventTwiceAndADifferentEventForTheSameClaimStillPayOnce() throws Exception {
        ClaimApproved claim = approved("300000.00", "500000.00", "10000.00");
        UUID eventId = UUID.randomUUID();
        int transfersBefore = gateway.transfersExecuted();

        send(claim, eventId, "corr-dup");                  // original
        send(claim, eventId, "corr-dup");                  // guard 1: same eventId (redelivery)
        UUID replayId = UUID.randomUUID();
        send(claim, replayId, "corr-dup");                 // guard 2: new eventId, same claim (replay / re-approval)

        awaitRecords(paymentEvent(claim.claimId(), EventTypes.PAYMENT_COMPLETED), 1);
        // the replay is the last of the three on this partition: once IT is marked processed, all three were handled
        await().atMost(TIMEOUT).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class, replayId) == 1);
        Thread.sleep(1000);   // give any (wrong) second payment time to be processed by the payment processor

        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE claim_id = ?", Integer.class,
                claim.claimId())).isEqualTo(1);
        assertThat(gateway.transfersExecuted() - transfersBefore).isEqualTo(1);
        assertThat(seen.stream().filter(paymentEvent(claim.claimId(), EventTypes.PAYMENT_INITIATED)).count())
                .isEqualTo(1);
    }

    @Test
    void databaseRejectsASecondPaymentForTheSameClaim() {
        // guard 3: even if both application checks had a bug, the UNIQUE constraint holds
        UUID claimId = UUID.randomUUID();
        SettlementCalculator.Result r = new SettlementCalculator().calculate(new BigDecimal("100"), BigDecimal.ZERO,
                new BigDecimal("1000"));
        Settlement s1 = settlements.saveAndFlush(new Settlement(claimId, "CLM-X", new BigDecimal("100"),
                new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("1000"), r, Instant.now()));
        payments.saveAndFlush(new Payment(claimId, "CLM-X", s1.getId(), new BigDecimal("100.00"), null));

        assertThatThrownBy(() -> payments.saveAndFlush(
                new Payment(claimId, "CLM-X", s1.getId(), new BigDecimal("100.00"), null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void amountAboveBankLimitIsDeclinedAndReportedAsFailed() throws Exception {
        ClaimApproved claim = approved("2500000.00", "3000000.00", "0.00");   // payable 25 lakh > 10 lakh bank limit

        send(claim, UUID.randomUUID(), "corr-decline");

        ConsumerRecord<String, String> failed =
                awaitRecords(paymentEvent(claim.claimId(), EventTypes.PAYMENT_FAILED), 1).get(0);
        assertThat(mapper.readTree(failed.value()).get("payload").get("reason").asText()).contains("Declined");
        assertThat(http.getForObject("/api/v1/payments?claimId=" + claim.claimId(), JsonNode.class)
                .get("status").asText()).isEqualTo("FAILED");
    }

    @Test
    void cappedSettlementPaysTheCoverageLimit() throws Exception {
        ClaimApproved claim = approved("900000.00", "500000.00", "20000.00");

        send(claim, UUID.randomUUID(), "corr-cap");

        awaitRecords(paymentEvent(claim.claimId(), EventTypes.PAYMENT_COMPLETED), 1);
        JsonNode view = http.getForObject("/api/v1/payments?claimId=" + claim.claimId(), JsonNode.class);
        assertThat(view.get("amount").decimalValue()).isEqualTo(new BigDecimal("500000.00"));
        assertThat(view.get("settlement").get("cappedAtLimit").asBoolean()).isTrue();
    }

    @Test
    void approvalWithoutCoverageTermsGoesToDeadLetterTopic() throws Exception {
        ClaimApproved claim = approved("1000.00", null, null);

        send(claim, UUID.randomUUID(), "corr-noterms");

        ConsumerRecord<String, String> dead = awaitRecords(r -> r.topic().equals(Topics.dlt(Topics.CLAIM_EVENTS))
                && claim.claimId().toString().equals(r.key()), 1).get(0);
        assertThat(header(dead, "kafka_dlt-exception-message")).contains("no coverage terms");
        assertThat(payments.existsByClaimId(claim.claimId())).isFalse();
    }

    @Test
    void unknownPaymentIs404() {
        assertThat(http.getForEntity("/api/v1/payments?claimId=" + UUID.randomUUID(), JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void publishedOpenApiContractIsUpToDate() throws Exception {
        String live = http.getForObject("/v3/api-docs/v1", String.class);
        com.claimflow.common.openapi.OpenApiContract.assertMatchesCommittedContract(live, "payment-service");
    }
}
