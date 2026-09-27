package com.claimflow.claim.claim;

import com.claimflow.claim.claim.dto.ClaimResponse;
import com.claimflow.claim.claim.dto.FnolRequest;
import com.claimflow.claim.claim.dto.HistoryResponse;
import com.claimflow.claim.claim.dto.UpdateStatusRequest;
import com.claimflow.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/claims")
@Tag(name = "Claims")
public class ClaimController {

    /**
     * Who performed the action, recorded in claim_history. A stand-in until Phase 7, when the
     * user comes from the authenticated JWT instead of a header.
     */
    static final String USER_HEADER = "X-User-Id";
    private static final String ANONYMOUS = "anonymous";

    public record AssignAdjusterRequest(@NotNull UUID adjusterId) {
    }

    private final ClaimService service;

    public ClaimController(ClaimService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "File a claim (First Notice of Loss)",
            description = "Send an Idempotency-Key to make retries safe: a repeated key returns the original claim with 200.")
    public ResponseEntity<ClaimResponse> fileFnol(
            @Valid @RequestBody FnolRequest request,
            @Parameter(description = "Client-generated unique key, e.g. a UUID")
            @RequestHeader(value = "Idempotency-Key", required = false) @Size(max = 100) String idempotencyKey,
            @RequestHeader(value = USER_HEADER, defaultValue = ANONYMOUS) String actor) {
        ClaimService.FnolResult result = service.fileFnol(request, idempotencyKey, actor);
        ClaimResponse body = ClaimResponse.from(result.claim());
        if (!result.created()) {
            return ResponseEntity.ok(body);   // replay: nothing new was created
        }
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{id}").buildAndExpand(result.claim().getId()).toUri())
                .body(body);
    }

    @GetMapping("/{claimId}")
    @Operation(summary = "Get a claim", description = "The ETag header is the claim's version; send it back as If-Match.")
    public ResponseEntity<ClaimResponse> get(@PathVariable UUID claimId) {
        return withETag(service.get(claimId));
    }

    @GetMapping
    @Operation(summary = "Claims of a policy, optionally filtered by status, newest first")
    public PageResponse<ClaimResponse> listForPolicy(
            @RequestParam UUID policyId,
            @RequestParam(required = false) ClaimStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageResponse.from(service.listForPolicy(policyId, status, page, size), ClaimResponse::from);
    }

    @PatchMapping("/{claimId}/status")
    @Operation(summary = "Move a claim to a new status (approve, reject, close)",
            description = "Optional If-Match: the ETag from GET. 412 if the claim changed since.")
    public ResponseEntity<ClaimResponse> updateStatus(
            @PathVariable UUID claimId, @Valid @RequestBody UpdateStatusRequest request,
            @RequestHeader(value = USER_HEADER, defaultValue = ANONYMOUS) String actor,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return withETag(service.updateStatus(claimId, request, actor, ETags.expectedVersion(ifMatch)));
    }

    @PostMapping("/{claimId}/assign-adjuster")
    @Operation(summary = "Assign (or reassign) an adjuster")
    public ResponseEntity<ClaimResponse> assignAdjuster(
            @PathVariable UUID claimId, @Valid @RequestBody AssignAdjusterRequest request,
            @RequestHeader(value = USER_HEADER, defaultValue = ANONYMOUS) String actor,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return withETag(service.assignAdjuster(claimId, request.adjusterId(), actor, ETags.expectedVersion(ifMatch)));
    }

    private static ResponseEntity<ClaimResponse> withETag(Claim claim) {
        return ResponseEntity.ok().eTag(ETags.of(claim)).body(ClaimResponse.from(claim));
    }

    @GetMapping("/{claimId}/history")
    @Operation(summary = "Audit trail of the claim, oldest first")
    public List<HistoryResponse> history(@PathVariable UUID claimId) {
        return service.history(claimId).stream().map(HistoryResponse::from).toList();
    }
}
