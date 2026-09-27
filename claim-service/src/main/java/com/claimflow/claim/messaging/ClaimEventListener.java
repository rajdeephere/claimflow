package com.claimflow.claim.messaging;

import com.claimflow.common.events.EventEnvelope;
import com.claimflow.common.events.Topics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka entry point. Kept thin: parse, then delegate to the transactional handler.
 * Exceptions propagate to the shared DefaultErrorHandler (retry with backoff, then DLT).
 * The offset is committed only after this method returns (ack-mode: record).
 */
@Component
public class ClaimEventListener {

    private static final Logger log = LoggerFactory.getLogger(ClaimEventListener.class);

    private final InboundEventHandler handler;
    private final ObjectMapper mapper;

    public ClaimEventListener(InboundEventHandler handler, ObjectMapper mapper) {
        this.handler = handler;
        this.mapper = mapper;
    }

    @KafkaListener(id = "claim-service-inbound", groupId = InboundEventHandler.CONSUMER,
            topics = {Topics.VALIDATION_EVENTS, Topics.PAYMENT_EVENTS})
    public void onMessage(ConsumerRecord<String, String> record) throws JsonProcessingException {
        EventEnvelope event = mapper.readValue(record.value(), EventEnvelope.class);
        log.info("Received {} {} for claim {} from {}-{}@{}", event.eventType(), event.eventId(),
                event.aggregateId(), record.topic(), record.partition(), record.offset());
        handler.handle(event);
    }
}
