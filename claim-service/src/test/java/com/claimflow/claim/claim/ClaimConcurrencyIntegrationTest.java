package com.claimflow.claim.claim;

import com.claimflow.claim.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lost-update protection (ADR-0026). Assertions are invariants ("exactly one winner", "every success
 * is in the audit trail"), never timing, so the tests are deterministic even though threads race.
 */
class ClaimConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    TestRestTemplate http;
    @Autowired
    ClaimRepository claims;
    @Autowired
    PlatformTransactionManager txManager;
    @Autowired
    JdbcTemplate jdbc;

    private UUID fileClaim() {
        JsonNode body = http.postForEntity("/api/v1/claims", Map.of("policyId", UUID.randomUUID(),
                "lossType", "COLLISION", "incidentDate", "2026-03-10", "description", "Concurrency test",
                "claimedAmount", "1000.00"), JsonNode.class).getBody();
        return UUID.fromString(body.get("id").asText());
    }

    private String newAdjuster() {
        return http.postForEntity("/api/v1/adjusters", Map.of("name", "Adj",
                "email", "adj-" + UUID.randomUUID() + "@example.com"), JsonNode.class).getBody().get("id").asText();
    }

    private ResponseEntity<JsonNode> assign(UUID claimId, String adjusterId, String ifMatch) {
        HttpHeaders h = new HttpHeaders();
        if (ifMatch != null) {
            h.setIfMatch(ifMatch);
        }
        return http.exchange("/api/v1/claims/" + claimId + "/assign-adjuster", HttpMethod.POST,
                new HttpEntity<>(Map.of("adjusterId", adjusterId), h), JsonNode.class);
    }

    private int assignmentsInHistory(UUID claimId) {
        return jdbc.queryForObject("SELECT count(*) FROM claim_history WHERE claim_id = ? AND event_type = 'ADJUSTER_ASSIGNED'",
                Integer.class, claimId);
    }

    /** Fires the same request from N threads released at the same instant. */
    private List<ResponseEntity<JsonNode>> race(int threads, Callable<ResponseEntity<JsonNode>> call) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<ResponseEntity<JsonNode>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<ResponseEntity<JsonNode>> results = new ArrayList<>();
            for (Future<ResponseEntity<JsonNode>> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    void staleEntityCannotOverwriteANewerCommit() {
        UUID claimId = fileClaim();
        UUID adjusterA = UUID.fromString(newAdjuster());
        UUID adjusterB = UUID.fromString(newAdjuster());
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);   // own EntityManager each

        tx.executeWithoutResult(outer -> {
            Claim stale = claims.findById(claimId).orElseThrow();            // A reads version N
            tx.executeWithoutResult(inner ->                                  // B reads N, updates, commits N+1
                    claims.findById(claimId).orElseThrow().assignAdjuster(adjusterB));

            stale.assignAdjuster(adjusterA);                                  // A changes its stale copy
            assertThatThrownBy(claims::flush)                                  // UPDATE ... WHERE version = N -> 0 rows
                    .isInstanceOf(ObjectOptimisticLockingFailureException.class);
            outer.setRollbackOnly();
        });

        // B's change survived; A's was rejected, not silently applied on top
        assertThat(claims.findById(claimId).orElseThrow().getAdjusterId()).isEqualTo(adjusterB);
    }

    @Test
    void ifMatchWithAStaleVersionIsRejectedWith412() {
        UUID claimId = fileClaim();
        String adjuster = newAdjuster();

        ResponseEntity<JsonNode> loaded = http.getForEntity("/api/v1/claims/" + claimId, JsonNode.class);
        String etag = loaded.getHeaders().getETag();
        assertThat(etag).isEqualTo("\"" + loaded.getBody().get("version").asLong() + "\"");

        ResponseEntity<JsonNode> first = assign(claimId, adjuster, etag);          // user 1, fresh copy
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getHeaders().getETag()).isNotEqualTo(etag);              // version moved on

        ResponseEntity<JsonNode> second = assign(claimId, newAdjuster(), etag);    // user 2, same old copy
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
        assertThat(second.getBody().get("message").asText()).contains("has changed since you loaded it");
        assertThat(assignmentsInHistory(claimId)).isEqualTo(1);                   // nothing written for user 2
    }

    @Test
    void tenUsersWithTheSameVersionExactlyOneWins() throws Exception {
        UUID claimId = fileClaim();
        String etag = http.getForEntity("/api/v1/claims/" + claimId, JsonNode.class).getHeaders().getETag();
        List<String> adjusters = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            adjusters.add(newAdjuster());
        }
        int[] next = {0};

        List<ResponseEntity<JsonNode>> results = race(10, () -> {
            String adjuster;
            synchronized (next) {
                adjuster = adjusters.get(next[0]++);
            }
            return assign(claimId, adjuster, etag);
        });

        List<HttpStatusCode> statuses = results.stream().map(ResponseEntity::getStatusCode).toList();
        assertThat(statuses).filteredOn(s -> s == HttpStatus.OK).hasSize(1);
        // losers: 412 if they checked after the winner committed, 409 if both passed the check and @Version decided
        assertThat(statuses).filteredOn(s -> s != HttpStatus.OK)
                .allMatch(s -> s == HttpStatus.PRECONDITION_FAILED || s == HttpStatus.CONFLICT);
        assertThat(assignmentsInHistory(claimId)).isEqualTo(1);
    }

    @Test
    void withoutIfMatchNoUpdateIsLostSilently() throws Exception {
        UUID claimId = fileClaim();
        List<String> adjusters = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            adjusters.add(newAdjuster());
        }
        int[] next = {0};
        long versionBefore = claims.findById(claimId).orElseThrow().getVersion();

        List<ResponseEntity<JsonNode>> results = race(10, () -> {
            String adjuster;
            synchronized (next) {
                adjuster = adjusters.get(next[0]++);
            }
            return assign(claimId, adjuster, null);
        });

        long ok = results.stream().filter(r -> r.getStatusCode() == HttpStatus.OK).count();
        assertThat(results).allMatch(r -> r.getStatusCode() == HttpStatus.OK || r.getStatusCode() == HttpStatus.CONFLICT);
        // every success is in the audit trail and bumped the version exactly once: no silent overwrite
        assertThat(assignmentsInHistory(claimId)).isEqualTo((int) ok);
        assertThat(claims.findById(claimId).orElseThrow().getVersion()).isEqualTo(versionBefore + ok);
        System.out.printf("withoutIfMatch: %d succeeded, %d got 409%n", ok, 10 - ok);
    }
}
