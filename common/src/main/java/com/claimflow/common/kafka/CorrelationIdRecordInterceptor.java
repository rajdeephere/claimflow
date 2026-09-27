package com.claimflow.common.kafka;

import com.claimflow.common.correlation.CorrelationId;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The Kafka-side twin of CorrelationIdFilter: before each record is handled, put its correlation ID
 * header into the MDC so the consumer's log lines (and any events it writes to its outbox) carry it;
 * clear it afterwards because listener threads are reused.
 */
public class CorrelationIdRecordInterceptor implements RecordInterceptor<Object, Object> {

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record,
                                                    Consumer<Object, Object> consumer) {
        Header header = record.headers().lastHeader(KafkaHeaderNames.CORRELATION_ID);
        String correlationId = header != null
                ? new String(header.value(), StandardCharsets.UTF_8)
                : UUID.randomUUID().toString();
        MDC.put(CorrelationId.MDC_KEY, correlationId);
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        MDC.remove(CorrelationId.MDC_KEY);
    }
}
