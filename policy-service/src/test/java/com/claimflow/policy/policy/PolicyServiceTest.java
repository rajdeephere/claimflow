package com.claimflow.policy.policy;

import com.claimflow.common.error.BusinessRuleException;
import com.claimflow.common.error.ResourceNotFoundException;
import com.claimflow.policy.customer.Customer;
import com.claimflow.policy.customer.CustomerRepository;
import com.claimflow.policy.policy.dto.CoverageCheckResponse;
import com.claimflow.policy.policy.dto.CoverageCheckResponse.Reason;
import com.claimflow.policy.policy.dto.CoverageRequest;
import com.claimflow.policy.policy.dto.CreatePolicyRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PolicyServiceTest {

    @Mock
    PolicyRepository policies;
    @Mock
    CustomerRepository customers;
    @Mock
    PolicyNumberGenerator policyNumbers;

    @InjectMocks
    PolicyService service;

    private final Customer customer =
            new Customer("Asha", "Rao", "asha@example.com", null, LocalDate.of(1990, 5, 1));

    private CreatePolicyRequest motorRequest(UUID customerId) {
        return new CreatePolicyRequest(customerId, ProductType.MOTOR,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), new BigDecimal("12000.00"),
                List.of(new CoverageRequest(CoverageType.COLLISION, new BigDecimal("500000.00"),
                        new BigDecimal("20000.00"))));
    }

    private Policy policyWithCollision() {
        Policy p = new Policy("POL-2026-000001", customer, ProductType.MOTOR,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), new BigDecimal("12000.00"));
        p.addCoverage(CoverageType.COLLISION, new BigDecimal("500000.00"), new BigDecimal("20000.00"));
        return p;
    }

    @Test
    void createsPolicyWithGeneratedNumberAndCoverages() {
        when(customers.findById(customer.getId())).thenReturn(Optional.of(customer));
        when(policyNumbers.next()).thenReturn("POL-2026-000042");
        when(policies.save(any(Policy.class))).thenAnswer(inv -> inv.getArgument(0));

        Policy created = service.create(motorRequest(customer.getId()));

        assertThat(created.getPolicyNumber()).isEqualTo("POL-2026-000042");
        assertThat(created.getCoverages()).hasSize(1);
        assertThat(created.getStatus()).isEqualTo(PolicyStatus.ACTIVE);
    }

    @Test
    void rejectsPolicyForUnknownCustomerWithoutSaving() {
        UUID unknown = UUID.randomUUID();
        when(customers.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(motorRequest(unknown)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("does not exist");
        verify(policies, never()).save(any());
        verify(policyNumbers, never()).next();   // don't burn a policy number on a failed request
    }

    @Test
    void getUnknownPolicyIsNotFound() {
        UUID id = UUID.randomUUID();
        when(policies.findWithCoveragesById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void coverageCheckCoveredReturnsLimitAndDeductible() {
        Policy policy = policyWithCollision();
        when(policies.findWithCoveragesById(policy.getId())).thenReturn(Optional.of(policy));

        CoverageCheckResponse r = service.checkCoverage(policy.getId(), CoverageType.COLLISION,
                LocalDate.of(2026, 3, 10));

        assertThat(r.covered()).isTrue();
        assertThat(r.reason()).isEqualTo(Reason.COVERED);
        assertThat(r.limitAmount()).isEqualByComparingTo("500000");
        assertThat(r.deductible()).isEqualByComparingTo("20000");
    }

    @Test
    void coverageCheckOutsidePeriod() {
        Policy policy = policyWithCollision();
        when(policies.findWithCoveragesById(policy.getId())).thenReturn(Optional.of(policy));

        CoverageCheckResponse r = service.checkCoverage(policy.getId(), CoverageType.COLLISION,
                LocalDate.of(2027, 2, 1));

        assertThat(r.covered()).isFalse();
        assertThat(r.reason()).isEqualTo(Reason.OUTSIDE_POLICY_PERIOD);
    }

    @Test
    void coverageCheckMissingCoverageType() {
        Policy policy = policyWithCollision();
        when(policies.findWithCoveragesById(policy.getId())).thenReturn(Optional.of(policy));

        CoverageCheckResponse r = service.checkCoverage(policy.getId(), CoverageType.THEFT,
                LocalDate.of(2026, 3, 10));

        assertThat(r.covered()).isFalse();
        assertThat(r.reason()).isEqualTo(Reason.COVERAGE_NOT_ON_POLICY);
        assertThat(r.limitAmount()).isNull();
    }

    @Test
    void coverageCheckCancelledPolicyReportsCancellationFirst() {
        Policy policy = policyWithCollision();
        policy.cancel();
        when(policies.findWithCoveragesById(policy.getId())).thenReturn(Optional.of(policy));

        CoverageCheckResponse r = service.checkCoverage(policy.getId(), CoverageType.COLLISION,
                LocalDate.of(2026, 3, 10));

        assertThat(r.reason()).isEqualTo(Reason.POLICY_CANCELLED);
    }
}
