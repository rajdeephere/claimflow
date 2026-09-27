package com.claimflow.payment.gateway;

/** Timeout or connection failure: the transfer may or may not have happened. */
public class GatewayUnavailableException extends RuntimeException {

    public GatewayUnavailableException(String message) {
        super(message);
    }
}
