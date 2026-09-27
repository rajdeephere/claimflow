package com.claimflow.common.correlation;

import org.slf4j.MDC;

public final class CorrelationId {

    public static final String HEADER = "X-Correlation-ID";
    public static final String MDC_KEY = "correlationId";

    private CorrelationId() {
    }

    /** The correlation ID of the current request/message, or null outside one. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
