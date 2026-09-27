package com.claimflow.policy.policy;

import com.claimflow.common.error.BusinessRuleException;
import com.claimflow.common.error.ResourceNotFoundException;
import com.claimflow.policy.customer.Customer;
import com.claimflow.policy.customer.CustomerRepository;
import com.claimflow.policy.policy.dto.CoverageCheckResponse;
import com.claimflow.policy.policy.dto.CoverageCheckResponse.Reason;
import com.claimflow.policy.policy.dto.CoverageRequest;
import com.claimflow.policy.policy.dto.CreatePolicyRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class PolicyService {

    private static final Logger log = LoggerFactory.getLogger(PolicyService.class);

    private final PolicyRepository policies;
    private final CustomerRepository customers;
    private final PolicyNumberGenerator policyNumbers;

    public PolicyService(PolicyRepository policies, CustomerRepository customers,
                         PolicyNumberGenerator policyNumbers) {
        this.policies = policies;
        this.customers = customers;
        this.policyNumbers = policyNumbers;
    }

    @Transactional
    public Policy create(CreatePolicyRequest req) {
        // The customer is referenced in the body, not the URL, so a missing one is a 422, not a 404.
        Customer customer = customers.findById(req.customerId())
                .orElseThrow(() -> new BusinessRuleException("Customer " + req.customerId() + " does not exist"));

        Policy policy = new Policy(policyNumbers.next(), customer, req.productType(),
                req.startDate(), req.endDate(), req.premium());
        for (CoverageRequest c : req.coverages()) {
            policy.addCoverage(c.coverageType(), c.limitAmount(), c.deductible());
        }
        Policy saved = policies.save(policy);
        log.info("Created policy {} ({}) for customer {}", saved.getPolicyNumber(), saved.getProductType(),
                customer.getId());
        return saved;
    }

    @Transactional(readOnly = true)
    public Policy get(UUID policyId) {
        return policies.findWithCoveragesById(policyId)
                .orElseThrow(() -> new ResourceNotFoundException("Policy", policyId));
    }

    @Transactional(readOnly = true)
    public List<Policy> listForCustomer(UUID customerId) {
        if (!customers.existsById(customerId)) {
            throw new ResourceNotFoundException("Customer", customerId);
        }
        return policies.findByCustomerIdOrderByStartDateDesc(customerId);
    }

    @Transactional
    public Policy cancel(UUID policyId) {
        Policy policy = get(policyId);
        policy.cancel();   // dirty checking: the change is flushed on commit, no save() needed
        log.info("Cancelled policy {}", policy.getPolicyNumber());
        return policy;
    }

    @Transactional(readOnly = true)
    public CoverageCheckResponse checkCoverage(UUID policyId, CoverageType type, LocalDate incidentDate) {
        Policy policy = get(policyId);
        Optional<Coverage> coverage = policy.findCoverage(type);

        Reason reason;
        if (policy.getStatus() == PolicyStatus.CANCELLED) {
            reason = Reason.POLICY_CANCELLED;
        } else if (!policy.isInForceOn(incidentDate)) {
            reason = Reason.OUTSIDE_POLICY_PERIOD;
        } else if (coverage.isEmpty()) {
            reason = Reason.COVERAGE_NOT_ON_POLICY;
        } else {
            reason = Reason.COVERED;
        }

        return new CoverageCheckResponse(policy.getId(), policy.getPolicyNumber(), type, incidentDate,
                reason == Reason.COVERED, reason,
                coverage.map(Coverage::getLimitAmount).orElse(null),
                coverage.map(Coverage::getDeductible).orElse(null));
    }
}
