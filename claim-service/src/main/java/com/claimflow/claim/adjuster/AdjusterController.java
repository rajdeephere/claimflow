package com.claimflow.claim.adjuster;

import com.claimflow.common.error.ConflictException;
import com.claimflow.common.openapi.ApiErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Adjusters are simple reference data, so the controller talks to the repository directly. */
@RestController
@RequestMapping("/api/v1/adjusters")
@Tag(name = "Adjusters")
public class AdjusterController {

    public record CreateAdjusterRequest(@NotBlank @Size(max = 150) String name,
                                        @NotBlank @Email @Size(max = 255) String email) {
    }

    public record AdjusterResponse(UUID id, String name, String email, boolean active, Instant createdAt) {
        static AdjusterResponse from(Adjuster a) {
            return new AdjusterResponse(a.getId(), a.getName(), a.getEmail(), a.isActive(), a.getCreatedAt());
        }
    }

    private final AdjusterRepository adjusters;

    public AdjusterController(AdjusterRepository adjusters) {
        this.adjusters = adjusters;
    }

    @PostMapping
    @Transactional
    @ApiResponse(responseCode = "201", description = "Created", headers = @Header(name = "Location", description = "URL of the new resource"))
    @ApiErrors({409})
    @Operation(summary = "Register a claims adjuster")
    public ResponseEntity<AdjusterResponse> create(@Valid @RequestBody CreateAdjusterRequest request) {
        String email = request.email().toLowerCase();
        if (adjusters.existsByEmail(email)) {
            throw new ConflictException("An adjuster with email " + email + " already exists");
        }
        Adjuster saved;
        try {
            saved = adjusters.saveAndFlush(new Adjuster(request.name(), email));
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("An adjuster with email " + email + " already exists");
        }
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{id}").buildAndExpand(saved.getId()).toUri())
                .body(AdjusterResponse.from(saved));
    }

    @GetMapping
    @Operation(summary = "List active adjusters")
    public List<AdjusterResponse> listActive() {
        return adjusters.findByActiveTrueOrderByName().stream().map(AdjusterResponse::from).toList();
    }
}
