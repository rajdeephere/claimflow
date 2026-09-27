package com.claimflow.claim.claim;

import com.claimflow.claim.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the V4 index. The test table is tiny, so the planner would rightly pick a
 * sequential scan; disabling seq scans for this one transaction asks "CAN this query be served by the
 * index, in order, without a sort?". If someone changes the query (e.g. sorts by another column) or
 * drops the index, this fails in CI instead of the slowdown showing up in production.
 * The real timings live in docs/sql-performance.md.
 */
class ClaimQueryPlanIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager txManager;

    // The exact shape Hibernate generates for ClaimRepository.findByPolicyIdAndStatus(..., Pageable)
    private static final String PAGE_QUERY = """
            EXPLAIN SELECT c1_0.id FROM claims c1_0
            WHERE c1_0.policy_id = ? AND c1_0.status = ?
            ORDER BY c1_0.created_at DESC FETCH FIRST 20 ROWS ONLY""";

    private List<String> plan() {
        return new TransactionTemplate(txManager).execute(s -> {
            jdbc.execute("SET LOCAL enable_seqscan = off");   // same connection: we're inside one transaction
            return jdbc.queryForList(PAGE_QUERY, String.class, UUID.randomUUID(), "CLOSED");
        });
    }

    @Test
    void claimsByPolicyAndStatusUseTheCompositeIndexWithoutSorting() {
        List<String> plan = plan();

        assertThat(String.join("\n", plan))
                .contains("idx_claim_policy_status_created")
                .doesNotContain("Sort");   // rows come out of the index already newest-first
    }
}
