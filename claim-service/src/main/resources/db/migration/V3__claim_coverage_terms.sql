-- Coverage terms as confirmed by validation (from the ClaimValidated event). Stored on the claim so
-- ClaimApproved can carry them to Payment Service (event-carried state transfer): Payment doesn't
-- need to call Policy Service, and settles on the terms that applied when the claim was validated.
ALTER TABLE claims ADD COLUMN coverage_limit NUMERIC(15,2);
ALTER TABLE claims ADD COLUMN deductible     NUMERIC(15,2);

ALTER TABLE claims ADD CONSTRAINT ck_claims_coverage_terms
    CHECK ((coverage_limit IS NULL AND deductible IS NULL)
        OR (coverage_limit > 0 AND deductible >= 0));
