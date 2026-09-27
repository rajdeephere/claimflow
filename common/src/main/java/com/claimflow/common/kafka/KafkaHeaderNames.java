package com.claimflow.common.kafka;

import com.claimflow.common.correlation.CorrelationId;

/** Kafka record headers set on every ClaimFlow message. */
public final class KafkaHeaderNames {

    /** Same name as the HTTP header, so one grep finds a transaction across HTTP and Kafka. */
    public static final String CORRELATION_ID = CorrelationId.HEADER;
    public static final String EVENT_TYPE = "eventType";
    public static final String EVENT_ID = "eventId";

    private KafkaHeaderNames() {
    }
}
