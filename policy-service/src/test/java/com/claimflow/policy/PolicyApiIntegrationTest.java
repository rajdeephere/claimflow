package com.claimflow.policy;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End to end through HTTP, Spring, JPA, Flyway and a real PostgreSQL 16 container.
 * Proves the migrations, constraints, entity mappings and error handling work together.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PolicyApiIntegrationTest {

    @Container
    @ServiceConnection   // Spring Boot points the datasource at this container automatically
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    TestRestTemplate http;

    private UUID createCustomer(String email) {
        ResponseEntity<JsonNode> res = http.postForEntity("/api/v1/customers", Map.of(
                "firstName", "Asha", "lastName", "Rao", "email", email, "dateOfBirth", "1990-05-01"),
                JsonNode.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(res.getBody().get("id").asText());
    }

    private ResponseEntity<JsonNode> createMotorPolicy(UUID customerId) {
        return http.postForEntity("/api/v1/policies", Map.of(
                "customerId", customerId,
                "productType", "MOTOR",
                "startDate", "2026-01-01",
                "endDate", "2026-12-31",
                "premium", "12000.00",
                "coverages", new Object[]{
                        Map.of("coverageType", "COLLISION", "limitAmount", "500000.00", "deductible", "20000.00"),
                        Map.of("coverageType", "THEFT", "limitAmount", "300000.00", "deductible", "10000.00")}),
                JsonNode.class);
    }

    @Test
    void fullPolicyLifecycle() {
        UUID customerId = createCustomer("asha-" + UUID.randomUUID() + "@example.com");

        // issue
        ResponseEntity<JsonNode> created = createMotorPolicy(customerId);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getLocation()).isNotNull();
        JsonNode policy = created.getBody();
        assertThat(policy.get("policyNumber").asText()).matches("POL-\\d{4}-\\d{6}");
        assertThat(policy.get("coverages")).hasSize(2);
        String policyId = policy.get("id").asText();

        // read back (coverages fetched with the policy)
        JsonNode fetched = http.getForObject("/api/v1/policies/" + policyId, JsonNode.class);
        assertThat(fetched.get("coverages")).hasSize(2);
        assertThat(fetched.get("premium").decimalValue()).isEqualByComparingTo("12000.00");

        // list for customer
        JsonNode list = http.getForObject("/api/v1/customers/" + customerId + "/policies", JsonNode.class);
        assertThat(list).hasSize(1);

        // coverage check: covered
        JsonNode check = http.getForObject("/api/v1/policies/" + policyId
                + "/coverage-check?coverageType=COLLISION&incidentDate=2026-03-10", JsonNode.class);
        assertThat(check.get("covered").asBoolean()).isTrue();
        assertThat(check.get("deductible").decimalValue()).isEqualByComparingTo("20000");

        // cancel, then the same check is no longer covered
        ResponseEntity<JsonNode> cancelled = http.postForEntity("/api/v1/policies/" + policyId + "/cancel",
                null, JsonNode.class);
        assertThat(cancelled.getBody().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.getBody().get("version").asLong()).isEqualTo(1L);   // @Version incremented

        JsonNode after = http.getForObject("/api/v1/policies/" + policyId
                + "/coverage-check?coverageType=COLLISION&incidentDate=2026-03-10", JsonNode.class);
        assertThat(after.get("reason").asText()).isEqualTo("POLICY_CANCELLED");

        // cancelling again is a conflict
        ResponseEntity<JsonNode> again = http.postForEntity("/api/v1/policies/" + policyId + "/cancel",
                null, JsonNode.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void duplicateCustomerEmailIsConflict() {
        String email = "dup-" + UUID.randomUUID() + "@example.com";
        createCustomer(email);

        ResponseEntity<JsonNode> res = http.postForEntity("/api/v1/customers", Map.of(
                "firstName", "Other", "lastName", "Person", "email", email.toUpperCase(),
                "dateOfBirth", "1985-01-01"), JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void policyForUnknownCustomerIs422() {
        ResponseEntity<JsonNode> res = createMotorPolicy(UUID.randomUUID());

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(res.getBody().get("message").asText()).contains("does not exist");
    }

    @Test
    void coverageNotOfferedForProductIs422() {
        UUID customerId = createCustomer("flood-" + UUID.randomUUID() + "@example.com");

        ResponseEntity<JsonNode> res = http.postForEntity("/api/v1/policies", Map.of(
                "customerId", customerId, "productType", "MOTOR",
                "startDate", "2026-01-01", "endDate", "2026-12-31", "premium", "9000.00",
                "coverages", new Object[]{
                        Map.of("coverageType", "FLOOD", "limitAmount", "100000.00", "deductible", "1000.00")}),
                JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
