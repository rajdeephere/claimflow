package com.claimflow.common.error;

/**
 * The client's If-Match version doesn't match the resource's current version: they're acting on a stale
 * copy. Maps to 412 Precondition Failed (RFC 9110 conditional requests).
 */
public class PreconditionFailedException extends RuntimeException {

    public PreconditionFailedException(String message) {
        super(message);
    }
}
