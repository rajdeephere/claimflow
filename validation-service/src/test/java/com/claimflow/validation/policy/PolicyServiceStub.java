package com.claimflow.validation.policy;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * A tiny fake Policy Service on a real socket (JDK HttpServer, no extra dependency). Responses are
 * queued per policy ID; the last one repeats. Records the correlation header of every request.
 */
public class PolicyServiceStub implements AutoCloseable {

    public record Response(int status, String body, long delayMs) {
    }

    private final HttpServer server;
    private final Map<UUID, Queue<Response>> responses = new ConcurrentHashMap<>();
    private final Map<UUID, Response> last = new ConcurrentHashMap<>();
    public final List<String> correlationIds = new CopyOnWriteArrayList<>();
    public final List<String> paths = new CopyOnWriteArrayList<>();

    public PolicyServiceStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().toString();
            paths.add(path);
            String corr = exchange.getRequestHeaders().getFirst("X-Correlation-ID");
            if (corr != null) correlationIds.add(corr);

            Response r = responseFor(path);
            if (r.delayMs() > 0) {
                try {
                    Thread.sleep(r.delayMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = r.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(r.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        });
        // Without an executor HttpServer handles requests on ONE thread: a deliberately slow response
        // in one test would make the next test's request time out (order-dependent, flaky).
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    private Response responseFor(String path) {
        for (UUID id : responses.keySet()) {
            if (path.contains(id.toString())) {
                Response next = responses.get(id).poll();
                if (next != null) {
                    last.put(id, next);
                    return next;
                }
                return last.getOrDefault(id, notFound(id));
            }
        }
        return new Response(404, "{\"status\":404,\"message\":\"No endpoint GET " + path + "\"}", 0);
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public PolicyServiceStub respond(UUID policyId, Response... queue) {
        responses.put(policyId, new ConcurrentLinkedQueue<>(List.of(queue)));
        last.remove(policyId);
        return this;
    }

    // ---- canned responses ----

    public static Response covered(UUID policyId, String limit, String deductible) {
        return coverage(policyId, true, "COVERED", limit, deductible);
    }

    public static Response coverage(UUID policyId, boolean covered, String reason, String limit, String deductible) {
        return new Response(200, """
                {"policyId":"%s","policyNumber":"POL-2026-000042","coverageType":"COLLISION",
                 "incidentDate":"2026-03-10","covered":%s,"reason":"%s","limitAmount":%s,"deductible":%s}"""
                .formatted(policyId, covered, reason, limit, deductible), 0);
    }

    public static Response notFound(UUID policyId) {
        return new Response(404, "{\"status\":404,\"message\":\"Policy not found: " + policyId + "\"}", 0);
    }

    public static Response serverError() {
        return new Response(503, "{\"status\":503,\"message\":\"Service Unavailable\"}", 0);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
