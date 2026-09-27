package com.claimflow.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * The gateway is the API boundary, so this is where a correlation ID is first assigned.
 * It is forwarded to the downstream service (which puts it in its MDC) and returned to the client.
 *
 * MDC is ThreadLocal and does not fit a reactive pipeline (one request hops across threads),
 * so here the ID travels on the request itself and is logged explicitly.
 */
@Component
public class CorrelationIdGlobalFilter implements GlobalFilter, Ordered {

    static final String HEADER = "X-Correlation-ID";

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdGlobalFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        String correlationId = StringUtils.hasText(incoming) ? incoming : UUID.randomUUID().toString();

        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(h -> h.set(HEADER, correlationId))
                .build();
        exchange.getResponse().getHeaders().set(HEADER, correlationId);

        long start = System.currentTimeMillis();
        return chain.filter(exchange.mutate().request(request).build())
                .doFinally(signal -> log.info("[{}] {} {} -> {} ({} ms)", correlationId,
                        request.getMethod(), request.getURI().getPath(),
                        exchange.getResponse().getStatusCode(), System.currentTimeMillis() - start));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
