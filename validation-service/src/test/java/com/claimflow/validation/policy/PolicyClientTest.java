package com.claimflow.validation.policy;

import com.claimflow.common.config.JsonConfig;
import com.claimflow.common.correlation.CorrelationId;
import com.claimflow.common.error.DependencyUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real HTTP against a stub server: status mapping, timeouts, correlation header. */
class PolicyClientTest {

    private static PolicyServiceStub stub;
    private static PolicyClient client;

    @BeforeAll
    static void start() throws Exception {
        stub = new PolicyServiceStub();
        ObjectMapper mapper = JsonConfig.configure(new ObjectMapper().registerModule(new JavaTimeModule()));
        RestClient.Builder builder = RestClient.builder()
                .messageConverters(c -> c.add(0, new MappingJackson2HttpMessageConverter(mapper)));
        client = new PolicyClient(builder, stub.baseUrl(), 500, 500);   // short timeouts for the test
    }

    @AfterAll
    static void stop() {
        stub.close();
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private static final LocalDate DATE = LocalDate.of(2026, 3, 10);

    @Test
    void returnsCoverageAndForwardsCorrelationId() {
        UUID policy = UUID.randomUUID();
        stub.respond(policy, PolicyServiceStub.covered(policy, "500000.00", "20000.00"));
        MDC.put(CorrelationId.MDC_KEY, "corr-client-1");

        Optional<CoverageCheck> result = client.checkCoverage(policy, "COLLISION", DATE);

        assertThat(result).isPresent();
        assertThat(result.get().reason()).isEqualTo(CoverageCheck.Reason.COVERED);
        assertThat(result.get().limitAmount()).isEqualTo(new BigDecimal("500000.00"));   // scale kept
        assertThat(stub.correlationIds).contains("corr-client-1");
        assertThat(stub.paths).anyMatch(p -> p.contains("coverageType=COLLISION") && p.contains("incidentDate=2026-03-10"));
    }

    @Test
    void policyNotFoundIsEmptyNotAnError() {
        UUID policy = UUID.randomUUID();
        stub.respond(policy, PolicyServiceStub.notFound(policy));

        assertThat(client.checkCoverage(policy, "COLLISION", DATE)).isEmpty();
    }

    @Test
    void unknown404IsTreatedAsUnavailableNotAsMissingPolicy() {
        // e.g. a wrong base URL: rejecting the claim as "policy not found" would be a serious mistake
        UUID policy = UUID.randomUUID();
        stub.respond(policy, new PolicyServiceStub.Response(404, "{\"message\":\"No endpoint GET /x\"}", 0));

        assertThatThrownBy(() -> client.checkCoverage(policy, "COLLISION", DATE))
                .isInstanceOf(DependencyUnavailableException.class);
    }

    @Test
    void serverErrorIsDependencyUnavailable() {
        UUID policy = UUID.randomUUID();
        stub.respond(policy, PolicyServiceStub.serverError());

        assertThatThrownBy(() -> client.checkCoverage(policy, "COLLISION", DATE))
                .isInstanceOf(DependencyUnavailableException.class)
                .hasMessageContaining("503");
    }

    @Test
    void slowResponseTimesOutAsDependencyUnavailable() {
        UUID policy = UUID.randomUUID();
        stub.respond(policy, new PolicyServiceStub.Response(200, "{}", 1500));   // read timeout is 500 ms

        assertThatThrownBy(() -> client.checkCoverage(policy, "COLLISION", DATE))
                .isInstanceOf(DependencyUnavailableException.class);
    }

    @Test
    void connectionRefusedIsDependencyUnavailable() {
        RestClient.Builder builder = RestClient.builder();
        PolicyClient deadClient = new PolicyClient(builder, "http://localhost:1", 300, 300);

        assertThatThrownBy(() -> deadClient.checkCoverage(UUID.randomUUID(), "COLLISION", DATE))
                .isInstanceOf(DependencyUnavailableException.class)
                .hasMessageContaining("unreachable");
    }

    @Test
    void badRequestIsOurBugNotRetryable() {
        UUID policy = UUID.randomUUID();
        stub.respond(policy, new PolicyServiceStub.Response(400, "{\"message\":\"Invalid value\"}", 0));

        assertThatThrownBy(() -> client.checkCoverage(policy, "ALIENS", DATE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
