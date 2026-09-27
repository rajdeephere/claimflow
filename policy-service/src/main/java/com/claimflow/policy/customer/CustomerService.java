package com.claimflow.policy.customer;

import com.claimflow.common.error.ConflictException;
import com.claimflow.common.error.ResourceNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class CustomerService {

    private final CustomerRepository customers;

    public CustomerService(CustomerRepository customers) {
        this.customers = customers;
    }

    @Transactional
    public Customer create(CreateCustomerRequest req) {
        String email = req.email().toLowerCase();
        // Fast, friendly check for the common case...
        if (customers.existsByEmail(email)) {
            throw new ConflictException("A customer with email " + email + " already exists");
        }
        try {
            // ...and the unique constraint for the race where two requests pass the check at once.
            return customers.saveAndFlush(new Customer(req.firstName(), req.lastName(), email, req.phone(),
                    req.dateOfBirth()));
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("A customer with email " + email + " already exists");
        }
    }

    @Transactional(readOnly = true)
    public Customer get(UUID id) {
        return customers.findById(id).orElseThrow(() -> new ResourceNotFoundException("Customer", id));
    }
}
