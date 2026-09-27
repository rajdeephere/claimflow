package com.claimflow.claim;

import com.claimflow.claim.claim.ClaimService;
import com.claimflow.claim.claim.ClaimStatus;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class ClaimApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    TestRestTemplate http;
    @Autowired
    ClaimService claimService;   // drives system transitions directly; the Kafka path is in ClaimKafkaIntegrationTest
    @Autowired
    JdbcTemplate jdbc;

    private static HttpHeaders headers(String user, String idempotencyKey, String correlationId) {
        HttpHeaders h = new HttpHeaders();
        if (user != null) h.set("X-User-Id", user);
        if (idempotencyKey != null) h.set("Idempotency-Key", idempotencyKey);
        if (correlationId != null) h.set("X-Correlation-ID", correlationId);
        return h;
    }

    private static Map<String, Object> fnolBody(UUID policyId) {
        return Map.of("policyId", policyId, "lossType", "COLLISION", "incidentDate", "2026-03-10",
                "description", "Rear-ended at a signal", "claimedAmount", "200000.00");
    }

    private ResponseEntity<JsonNode> fileFnol(UUID policyId, String key, String correlationId) {
        return http.exchange("/api/v1/claims", HttpMethod.POST,
                new HttpEntity<>(fnolBody(policyId), headers("agent-7", key, correlationId)), JsonNode.class);
    }

    private ResponseEntity<JsonNode> patchStatus(String claimId, Map<String, Object> body, String user) {
        return http.exchange("/api/v1/claims/" + claimId + "/status", HttpMethod.PATCH,
                new HttpEntity<>(body, headers(user, null, null)), JsonNode.class);
    }

    private String createAdjuster() {
        return http.postForEntity("/api/v1/adjusters",
                        Map.of("name", "Ravi Kumar", "email", "ravi-" + UUID.randomUUID() + "@example.com"),
                        JsonNode.class)
                .getBody().get("id").asText();
    }

    @Test
    void fullHappyPathWithAuditTrail() {
        // FNOL
        ResponseEntity<JsonNode> filed = fileFnol(UUID.randomUUID(), null, "corr-happy");
        assertThat(filed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String claimId = filed.getBody().get("id").asText();
        assertThat(filed.getBody().get("claimNumber").asText()).matches("CLM-\\d{4}-\\d{6}");
        UUID id = UUID.fromString(claimId);

        // validation passed (system; a Kafka consumer will call this in Phase 4)
        claimService.applySystemTransition(id, ClaimStatus.UNDER_REVIEW, "Policy in force, COLLISION covered");

        // adjuster assigned and approves
        String adjusterId = createAdjuster();
        ResponseEntity<JsonNode> assigned = http.exchange("/api/v1/claims/" + claimId + "/assign-adjuster",
                HttpMethod.POST, new HttpEntity<>(Map.of("adjusterId", adjusterId), headers("mgr-1", null, null)),
                JsonNode.class);
        assertThat(assigned.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> approved = patchStatus(claimId,
                Map.of("targetStatus", "APPROVED", "approvedAmount", "180000.00"), "adj-ravi");
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        // approval hands the claim straight to payment (APPROVED + SETTLEMENT_PENDING in one transaction)
        assertThat(approved.getBody().get("status").asText()).isEqualTo("SETTLEMENT_PENDING");
        assertThat(approved.getBody().get("approvedAmount").decimalValue()).isEqualByComparingTo("180000.00");

        // payment steps (system), then closure (user)
        claimService.applySystemTransition(id, ClaimStatus.PAYMENT_INITIATED, "Payment P-1");
        claimService.applySystemTransition(id, ClaimStatus.SETTLED, "Paid 180000.00");
        ResponseEntity<JsonNode> closed = patchStatus(claimId, Map.of("targetStatus", "CLOSED"), "adj-ravi");
        assertThat(closed.getBody().get("status").asText()).isEqualTo("CLOSED");
        assertThat(closed.getBody().get("allowedNextStatuses")).isEmpty();

        // audit trail: every step, in order, with who and (for the FNOL) the correlation ID
        JsonNode history = http.getForObject("/api/v1/claims/" + claimId + "/history", JsonNode.class);
        List<String> events = new ArrayList<>();
        history.forEach(h -> events.add(h.get("eventType").asText()));
        assertThat(events).containsExactly("CLAIM_CREATED", "CLAIM_VALIDATED", "ADJUSTER_ASSIGNED",
                "CLAIM_APPROVED", "SETTLEMENT_REQUESTED", "PAYMENT_INITIATED", "PAYMENT_COMPLETED", "CLAIM_CLOSED");
        assertThat(history.get(0).get("performedBy").asText()).isEqualTo("agent-7");
        assertThat(history.get(0).get("correlationId").asText()).isEqualTo("corr-happy");
        assertThat(history.get(1).get("performedBy").asText()).isEqualTo("system");
        assertThat(history.get(3).get("performedBy").asText()).isEqualTo("adj-ravi");
        assertThat(history.get(4).get("performedBy").asText()).isEqualTo("system");   // hand-off to payment

        // CLOSED -> APPROVED is rejected (the spec's example) and nothing is written
        ResponseEntity<JsonNode> reopen = patchStatus(claimId,
                Map.of("targetStatus", "APPROVED", "approvedAmount", "1000"), "adj-ravi");
        assertThat(reopen.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.getForObject("/api/v1/claims/" + claimId + "/history", JsonNode.class)).hasSize(8);
    }

    @Test
    void userCannotSetSystemOnlyStatus() {
        String claimId = fileFnol(UUID.randomUUID(), null, null).getBody().get("id").asText();

        ResponseEntity<JsonNode> res = patchStatus(claimId, Map.of("targetStatus", "UNDER_REVIEW"), "adj");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void retriedFnolWithSameKeyReturnsOriginalClaim() {
        String key = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> first = fileFnol(UUID.randomUUID(), key, null);
        ResponseEntity<JsonNode> retry = fileFnol(UUID.randomUUID(), key, null);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getBody().get("id").asText()).isEqualTo(first.getBody().get("id").asText());
    }

    @Test
    void concurrentFnolWithSameKeyCreatesExactlyOneClaim() throws Exception {
        String key = UUID.randomUUID().toString();
        UUID policyId = UUID.randomUUID();
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<ResponseEntity<JsonNode>>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<ResponseEntity<JsonNode>> call = () -> {
                    start.await();   // release all threads at once to force the race
                    return fileFnol(policyId, key, null);
                };
                results.add(pool.submit(call));
            }
            start.countDown();

            List<String> ids = new ArrayList<>();
            int created = 0;
            for (Future<ResponseEntity<JsonNode>> f : results) {
                ResponseEntity<JsonNode> res = f.get();
                assertThat(res.getStatusCode().is2xxSuccessful()).as("status %s", res.getStatusCode()).isTrue();
                if (res.getStatusCode() == HttpStatus.CREATED) created++;
                ids.add(res.getBody().get("id").asText());
            }

            assertThat(created).isEqualTo(1);
            assertThat(ids).containsOnly(ids.get(0));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM claims WHERE idempotency_key = ?",
                    Integer.class, key)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void listsClaimsOfPolicyByStatusNewestFirst() {
        UUID policyId = UUID.randomUUID();
        String older = fileFnol(policyId, null, null).getBody().get("id").asText();
        String newer = fileFnol(policyId, null, null).getBody().get("id").asText();
        String rejected = fileFnol(policyId, null, null).getBody().get("id").asText();
        claimService.applySystemTransition(UUID.fromString(rejected), ClaimStatus.REJECTED, "Policy not in force");

        JsonNode page = http.getForObject("/api/v1/claims?policyId=" + policyId + "&status=SUBMITTED&size=10",
                JsonNode.class);

        assertThat(page.get("totalElements").asInt()).isEqualTo(2);
        assertThat(page.get("content").get(0).get("id").asText()).isEqualTo(newer);
        assertThat(page.get("content").get(1).get("id").asText()).isEqualTo(older);
    }
}
