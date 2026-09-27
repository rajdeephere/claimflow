package com.claimflow.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.TimeoutException;

/**
 * Turns gateway-level failures into the same error shape the services return:
 * downstream unreachable -> 503, downstream too slow -> 504.
 * Ordered before Spring Boot's default handler (which would answer 500).
 */
@Component
@Order(-2)
public class GatewayErrorHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.error(ex);
        }

        HttpStatusCode status;
        String message;
        if (hasCause(ex, ConnectException.class)) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
            message = "Downstream service is unavailable";
        } else if (hasCause(ex, TimeoutException.class)) {
            status = HttpStatus.GATEWAY_TIMEOUT;
            message = "Downstream service timed out";
        } else if (ex instanceof ResponseStatusException rse) {
            status = rse.getStatusCode();
            message = rse.getReason() != null ? rse.getReason() : status.toString();
        } else {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            message = "Unexpected gateway error";
        }

        String correlationId = response.getHeaders().getFirst(CorrelationIdGlobalFilter.HEADER);
        String path = exchange.getRequest().getURI().getPath();
        log.warn("[{}] {} {} failed: {} ({})", correlationId, exchange.getRequest().getMethod(), path,
                status.value(), ex.toString());

        String reason = status instanceof HttpStatus hs ? hs.getReasonPhrase() : String.valueOf(status.value());
        String body = """
                {"timestamp":"%s","status":%d,"error":"%s","message":"%s","path":"%s","correlationId":"%s","violations":[]}"""
                .formatted(Instant.now(), status.value(), reason, message, path, correlationId);

        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    private static boolean hasCause(Throwable ex, Class<? extends Throwable> type) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
