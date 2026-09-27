package com.claimflow.policy.policy;

import com.claimflow.common.error.ResourceNotFoundException;
import com.claimflow.policy.customer.Customer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Web layer only (MockMvc + mocked service): request validation, status codes, error body. */
@WebMvcTest(PolicyController.class)
class PolicyControllerTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    PolicyService service;

    private static final String VALID_BODY = """
            {
              "customerId": "%s",
              "productType": "MOTOR",
              "startDate": "2026-01-01",
              "endDate": "2026-12-31",
              "premium": 12000.00,
              "coverages": [
                { "coverageType": "COLLISION", "limitAmount": 500000.00, "deductible": 20000.00 }
              ]
            }""";

    @Test
    void createReturns201WithLocationHeader() throws Exception {
        Customer customer = new Customer("Asha", "Rao", "asha@example.com", null, LocalDate.of(1990, 5, 1));
        Policy policy = new Policy("POL-2026-000001", customer, ProductType.MOTOR,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), new BigDecimal("12000.00"));
        when(service.create(any())).thenReturn(policy);

        mvc.perform(post("/api/v1/policies").contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.formatted(customer.getId())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/policies/" + policy.getId())))
                .andExpect(jsonPath("$.policyNumber").value("POL-2026-000001"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void invalidBodyReturns400WithFieldViolations() throws Exception {
        String body = """
                {
                  "productType": "MOTOR",
                  "startDate": "2026-12-31",
                  "endDate": "2026-01-01",
                  "premium": -5,
                  "coverages": []
                }""";

        mvc.perform(post("/api/v1/policies").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Correlation-ID", "test-corr-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.correlationId").value("test-corr-1"))
                .andExpect(jsonPath("$.violations[*].field", hasItem("customerId")))
                .andExpect(jsonPath("$.violations[*].field", hasItem("premium")))
                .andExpect(jsonPath("$.violations[*].field", hasItem("coverages")))
                .andExpect(jsonPath("$.violations[*].field", hasItem("endDateAfterStartDate")));
    }

    @Test
    void unknownEnumInBodyReturns400() throws Exception {
        String body = VALID_BODY.formatted(UUID.randomUUID()).replace("MOTOR", "SPACESHIP");

        mvc.perform(post("/api/v1/policies").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownPolicyReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.get(id)).thenThrow(new ResourceNotFoundException("Policy", id));

        mvc.perform(get("/api/v1/policies/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Policy not found: " + id));
    }

    @Test
    void malformedUuidInPathReturns400() throws Exception {
        mvc.perform(get("/api/v1/policies/not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void coverageCheckWithInvalidCoverageTypeReturns400() throws Exception {
        mvc.perform(get("/api/v1/policies/{id}/coverage-check", UUID.randomUUID())
                        .param("coverageType", "ALIENS")
                        .param("incidentDate", "2026-03-10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Invalid value 'ALIENS' for 'coverageType'")))
                .andExpect(jsonPath("$.message", containsString("COLLISION")));
    }

    // Not written for a specific bug: these prove the whole class of standard MVC errors keeps
    // its correct status (BUG-006), not just the three cases that failed first.
    @Test
    void wrongHttpMethodReturns405() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/policies/{id}", UUID.randomUUID()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    void wrongContentTypeReturns415() throws Exception {
        mvc.perform(post("/api/v1/policies").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415));
    }

    @Test
    void coverageCheckWithoutRequiredParamReturns400() throws Exception {
        mvc.perform(get("/api/v1/policies/{id}/coverage-check", UUID.randomUUID())
                        .param("coverageType", "COLLISION"))
                .andExpect(status().isBadRequest());
    }
}
