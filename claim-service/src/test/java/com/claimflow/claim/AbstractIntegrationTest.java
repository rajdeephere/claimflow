package com.claimflow.claim;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Real PostgreSQL + real Kafka for integration tests.
 *
 * Singleton containers: started once per JVM and shared by every test class that extends this
 * (Ryuk removes them at the end), instead of a fresh pair per class. Kafka uses the same
 * apache/kafka image as docker-compose.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "claimflow.kafka.retry.initial-interval-ms=100",   // keep retry-then-DLT tests fast
        "claimflow.outbox.poll-interval-ms=100"
})
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    // Boot 3.3's @ServiceConnection doesn't know org.testcontainers.kafka.KafkaContainer yet, so wire it by hand.
    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }
}
