package com.claimflow.common.error;

/**
 * The request conflicts with the current state of the resource (duplicate policy number,
 * invalid state transition...). Maps to 409 Conflict.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
