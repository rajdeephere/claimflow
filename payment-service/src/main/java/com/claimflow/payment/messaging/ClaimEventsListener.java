package com.claimflow.payment.messaging;

import com.claimflow.common.events.EventEnvelope;
import com.claimflow.common.events.EventTypes;
import com.claimflow.common.events.Topics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ClaimEventsListener {

    private final ClaimApprovedHandler handler;
    private final ObjectMapper mapper;

    public ClaimEventsListener(ClaimApprovedHandler handler, ObjectMapper mapper) {
        this.handler = handler;
        this.mapper = mapper;
    }

    @KafkaListener(id = ClaimApprovedHandler.CONSUMER, groupId = ClaimApprovedHandler.CONSUMER,
            topics = Topics.CLAIM_EVENTS)
    public void onMessage(ConsumerRecord<String, String> record) throws Exception {
        EventEnvelope event = mapper.readValue(record.value(), EventEnvelope.class);
        if (EventTypes.CLAIM_APPROVED.equals(event.eventType())) {
            handler.handle(event);
        }
        // ClaimSubmitted / Rejected / Closed are not Payment's concern
    }
}
