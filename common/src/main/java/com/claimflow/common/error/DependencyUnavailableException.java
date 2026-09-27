package com.claimflow.common.error;

/**
 * A service we depend on is down, timing out or returning 5xx. Nothing is wrong with the message or
 * the data, so a Kafka consumer should keep retrying with a long backoff instead of dead-lettering
 * after a few seconds (see KafkaInfrastructureConfig).
 */
public class DependencyUnavailableException extends RuntimeException {

    public DependencyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
