package com.claimflow.policy.policy;

import com.claimflow.policy.policy.dto.CoverageCheckResponse;
import com.claimflow.policy.policy.dto.CreatePolicyRequest;
import com.claimflow.policy.policy.dto.PolicyResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Policies")
public class PolicyController {

    private final PolicyService service;

    public PolicyController(PolicyService service) {
        this.service = service;
    }

    @PostMapping("/policies")
    @Operation(summary = "Issue a policy with its coverages")
    public ResponseEntity<PolicyResponse> create(@Valid @RequestBody CreatePolicyRequest request) {
        Policy policy = service.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(policy.getId()).toUri();
        return ResponseEntity.created(location).body(PolicyResponse.from(policy));
    }

    @GetMapping("/policies/{policyId}")
    @Operation(summary = "Get a policy with its coverages")
    public PolicyResponse get(@PathVariable UUID policyId) {
        return PolicyResponse.from(service.get(policyId));
    }

    @GetMapping("/customers/{customerId}/policies")
    @Operation(summary = "List a customer's policies, newest first")
    public List<PolicyResponse> listForCustomer(@PathVariable UUID customerId) {
        return service.listForCustomer(customerId).stream().map(PolicyResponse::from).toList();
    }

    // An action on the resource rather than a PATCH of "status": cancelling is a business operation
    // with its own rules, not a free-form field update.
    @PostMapping("/policies/{policyId}/cancel")
    @Operation(summary = "Cancel a policy")
    public PolicyResponse cancel(@PathVariable UUID policyId) {
        return PolicyResponse.from(service.cancel(policyId));
    }

    @GetMapping("/policies/{policyId}/coverage-check")
    @Operation(summary = "Is the policy in force and does it cover this loss type on the incident date?")
    public CoverageCheckResponse checkCoverage(
            @PathVariable UUID policyId,
            @RequestParam CoverageType coverageType,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate incidentDate) {
        return service.checkCoverage(policyId, coverageType, incidentDate);
    }
}
