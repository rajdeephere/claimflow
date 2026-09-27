package com.claimflow.common.kafka;

import com.claimflow.common.error.BusinessRuleException;
import com.claimflow.common.error.ConflictException;
import com.claimflow.common.events.Topics;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.DeserializationException;

import java.util.List;
import java.util.stream.Stream;

/**
 * Shared Kafka setup for every service that uses Kafka. Spring Boot applies a single
 * {@link RecordInterceptor} bean and a single {@code CommonErrorHandler} bean to its listener
 * container factory automatically.
 *
 * Only active when spring-kafka is on the classpath (policy-service doesn't use Kafka).
 */
@Configuration
@ConditionalOnClass(name = "org.springframework.kafka.core.KafkaTemplate")
public class KafkaInfrastructureConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaInfrastructureConfig.class);

    @Bean
    public RecordInterceptor<Object, Object> correlationIdRecordInterceptor() {
        return new CorrelationIdRecordInterceptor();
    }

    /**
     * Retry transient failures with exponential backoff, then publish to {@code <topic>.DLT}.
     * Errors that can never succeed on retry go to the DLT immediately:
     * malformed JSON, and business outcomes like an invalid state transition.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaOperations<?, ?> kafkaOperations,
                                                 @Value("${claimflow.kafka.retry.initial-interval-ms:500}") long initial,
                                                 @Value("${claimflow.kafka.retry.max-retries:3}") int maxRetries) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaOperations);
        // 500 ms, 1 s, 2 s ... then give up and publish to the DLT
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(maxRetries);
        backOff.setInitialInterval(initial);
        backOff.setMultiplier(2.0);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(
                JsonProcessingException.class,      // malformed message: retrying won't fix it
                DeserializationException.class,
                ConflictException.class,            // e.g. InvalidStateTransitionException
                BusinessRuleException.class,
                IllegalArgumentException.class);
        // Log the ROOT cause: the top-level exception is always Spring's ListenerExecutionFailedException
        // wrapper, whose message ("Listener method ... threw exception") says nothing useful.
        handler.setRetryListeners(new RetryListener() {
            @Override
            public void failedDelivery(ConsumerRecord<?, ?> record, Exception ex, int attempt) {
                log.warn("Kafka delivery attempt {} failed for {}-{}@{} (key {}): {}", attempt, record.topic(),
                        record.partition(), record.offset(), record.key(), rootCause(ex));
            }

            @Override
            public void recovered(ConsumerRecord<?, ?> record, Exception ex) {
                log.error("Sent {}-{}@{} (key {}) to {} after failure: {}", record.topic(), record.partition(),
                        record.offset(), record.key(), Topics.dlt(record.topic()), rootCause(ex));
            }
        });
        return handler;
    }

    private static String rootCause(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    /**
     * Declares the topics and their DLTs. KafkaAdmin creates any that are missing at startup
     * (broker auto-creation is disabled). In production, topics would be managed by the platform
     * team or IaC instead.
     */
    @Bean
    public KafkaAdmin.NewTopics claimFlowTopics() {
        List<NewTopic> topics = Stream.of(Topics.CLAIM_EVENTS, Topics.VALIDATION_EVENTS, Topics.PAYMENT_EVENTS)
                .flatMap(t -> Stream.of(t, Topics.dlt(t)))
                // a DLT needs at least as many partitions: the recoverer keeps the original partition
                .map(t -> TopicBuilder.name(t).partitions(Topics.PARTITIONS).replicas(1).build())
                .toList();
        return new KafkaAdmin.NewTopics(topics.toArray(NewTopic[]::new));
    }
}
