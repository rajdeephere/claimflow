package com.claimflow.validation.policy;

import com.claimflow.common.correlation.CorrelationId;
import com.claimflow.common.error.DependencyUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Synchronous call to Policy Service (ADR-0004), with short timeouts and the correlation ID forwarded.
 *
 * Outcomes are classified because the Kafka error handler treats them differently:
 * <ul>
 *   <li>200: the coverage answer</li>
 *   <li>404 "Policy not found": {@code Optional.empty()}, a business answer (the claim will be rejected)</li>
 *   <li>any other 404 (e.g. a wrong base URL), 5xx, timeout, connection refused:
 *       {@link DependencyUnavailableException}, retried for minutes, never a rejection</li>
 *   <li>other 4xx: our request is wrong, a bug, so {@link IllegalArgumentException} (non-retryable, DLT)</li>
 * </ul>
 */
@Component
public class PolicyClient {

    private static final Logger log = LoggerFactory.getLogger(PolicyClient.class);
    private static final String POLICY_NOT_FOUND = "Policy not found";

    private final RestClient rest;

    public PolicyClient(RestClient.Builder builder,
                        @Value("${claimflow.policy-service.base-url}") String baseUrl,
                        @Value("${claimflow.policy-service.connect-timeout-ms:2000}") long connectTimeoutMs,
                        @Value("${claimflow.policy-service.read-timeout-ms:3000}") long readTimeoutMs) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeoutMs)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.rest = builder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .requestInterceptor((request, body, execution) -> {
                    String correlationId = CorrelationId.current();
                    if (correlationId != null) {
                        request.getHeaders().set(CorrelationId.HEADER, correlationId);
                    }
                    return execution.execute(request, body);
                })
                .build();
    }

    public Optional<CoverageCheck> checkCoverage(UUID policyId, String coverageType, LocalDate incidentDate) {
        try {
            return rest.get()
                    .uri("/api/v1/policies/{id}/coverage-check?coverageType={type}&incidentDate={date}",
                            policyId, coverageType, incidentDate)
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.is2xxSuccessful()) {
                            return Optional.ofNullable(response.bodyTo(CoverageCheck.class));
                        }
                        if (status.value() == 404) {
                            JsonNode error = response.bodyTo(JsonNode.class);
                            String message = error == null ? "" : error.path("message").asText("");
                            if (message.startsWith(POLICY_NOT_FOUND)) {
                                return Optional.empty();
                            }
                            // A 404 that isn't Policy Service saying "no such policy" is a routing/config
                            // problem. Rejecting the claim on it would be wrong, so retry instead.
                            throw new DependencyUnavailableException(
                                    "Policy Service returned 404 without 'Policy not found': " + message, null);
                        }
                        if (status.is5xxServerError()) {
                            throw new DependencyUnavailableException("Policy Service returned " + status, null);
                        }
                        throw new IllegalArgumentException("Policy Service rejected coverage-check for policy "
                                + policyId + " (" + coverageType + "): " + status);
                    });
        } catch (ResourceAccessException e) {   // connection refused, timeouts
            log.warn("Policy Service unreachable for policy {}: {}", policyId, e.getMessage());
            throw new DependencyUnavailableException("Policy Service unreachable: " + e.getMessage(), e);
        }
    }
}
