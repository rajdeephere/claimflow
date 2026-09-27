package com.claimflow.claim.claim;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ClaimController.class)
class ClaimControllerTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    ClaimService service;

    private static final String FNOL = """
            { "policyId": "%s", "lossType": "COLLISION", "incidentDate": "%s",
              "description": "Rear-ended at a signal", "claimedAmount": 200000.00 }""";

    private static Claim claim() {
        return new Claim("CLM-2026-000001", UUID.randomUUID(), LossType.COLLISION, LocalDate.of(2026, 3, 10),
                Instant.now(), "Rear-ended", new BigDecimal("200000.00"), null);
    }

    @Test
    void newFnolReturns201WithLocation() throws Exception {
        Claim claim = claim();
        when(service.fileFnol(any(), eq("key-1"), eq("agent-7"))).thenReturn(new ClaimService.FnolResult(claim, true));

        mvc.perform(post("/api/v1/claims").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "key-1").header("X-User-Id", "agent-7")
                        .content(FNOL.formatted(UUID.randomUUID(), "2026-03-10")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/claims/" + claim.getId())))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.allowedNextStatuses", hasItem("UNDER_REVIEW")));
    }

    @Test
    void replayedFnolReturns200() throws Exception {
        when(service.fileFnol(any(), eq("key-1"), any())).thenReturn(new ClaimService.FnolResult(claim(), false));

        mvc.perform(post("/api/v1/claims").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", "key-1")
                        .content(FNOL.formatted(UUID.randomUUID(), "2026-03-10")))
                .andExpect(status().isOk());
    }

    @Test
    void futureIncidentDateIs400() throws Exception {
        mvc.perform(post("/api/v1/claims").contentType(MediaType.APPLICATION_JSON)
                        .content(FNOL.formatted(UUID.randomUUID(), LocalDate.now().plusDays(1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[*].field", hasItem("incidentDate")));
    }

    @Test
    void tooLongIdempotencyKeyIs400() throws Exception {
        mvc.perform(post("/api/v1/claims").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "k".repeat(101))
                        .content(FNOL.formatted(UUID.randomUUID(), "2026-03-10")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[*].field", hasItem("idempotencyKey")));
    }

    @Test
    void invalidTransitionIs409() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.updateStatus(eq(id), any(), any(), any())).thenThrow(
                new InvalidStateTransitionException("CLM-2026-000001", ClaimStatus.CLOSED, ClaimStatus.APPROVED));

        mvc.perform(patch("/api/v1/claims/{id}/status", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"APPROVED\",\"approvedAmount\":1000}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("CLOSED is a final state")));
    }

    @Test
    void unknownTargetStatusIs400() throws Exception {
        mvc.perform(patch("/api/v1/claims/{id}/status", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"PAID_TWICE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pageSizeAboveLimitIs400() throws Exception {
        mvc.perform(get("/api/v1/claims").param("policyId", UUID.randomUUID().toString()).param("size", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[*].field", hasItem("size")));
    }

    @Test
    void listRequiresPolicyId() throws Exception {
        mvc.perform(get("/api/v1/claims"))
                .andExpect(status().isBadRequest());
    }
}
