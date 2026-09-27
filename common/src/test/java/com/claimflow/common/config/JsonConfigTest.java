package com.claimflow.common.config;

import com.claimflow.common.events.EventEnvelope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Money must survive the exact path an event takes: object -> JsonNode -> JSON text -> JsonNode -> object.
 * Compares with equals() (value AND scale), not compareTo(), because 2E+5 compareTo 200000.00 == 0 would hide the bug.
 */
class JsonConfigTest {

    // largestColumnValue: the biggest NUMERIC(15,2) amount; eighteenDigits: more than a double can round-trip (measured: becomes ...456.8)
    record Amounts(BigDecimal claimed, BigDecimal largestColumnValue, BigDecimal small, BigDecimal eighteenDigits) {
    }

    private static final Amounts AMOUNTS = new Amounts(new BigDecimal("200000.00"),
            new BigDecimal("9999999999999.99"), new BigDecimal("0.10"), new BigDecimal("1234567890123456.78"));

    private static Amounts roundTrip(ObjectMapper mapper) throws Exception {
        EventEnvelope out = new EventEnvelope(UUID.randomUUID(), "Test", 1, Instant.now(), "test",
                UUID.randomUUID(), "c", mapper.valueToTree(AMOUNTS));
        String json = mapper.writeValueAsString(out);
        EventEnvelope in = mapper.readValue(json, EventEnvelope.class);
        return mapper.treeToValue(in.payload(), Amounts.class);
    }

    @Test
    void defaultJacksonDamagesMoneyInEventPayloads() throws Exception {
        // Documents the bug this config fixes: the defaults lose scale and precision.
        Amounts back = roundTrip(new ObjectMapper().registerModule(new JavaTimeModule()));

        assertThat(back.claimed()).isNotEqualTo(AMOUNTS.claimed());               // scale lost
        assertThat(back.claimed()).isEqualByComparingTo(AMOUNTS.claimed());       // ...which compareTo hides
        assertThat(back.small()).isNotEqualTo(AMOUNTS.small());                   // 0.10 -> 0.1
        assertThat(back.eighteenDigits()).isNotEqualByComparingTo(AMOUNTS.eighteenDigits());   // value rounded
        // NOT damaged in value: 15 significant digits fit in a double (this is why NUMERIC(15,2) is safe)
        assertThat(back.largestColumnValue()).isEqualByComparingTo(AMOUNTS.largestColumnValue());
    }

    @Test
    void configuredMapperPreservesValueAndScaleExactly() throws Exception {
        ObjectMapper mapper = JsonConfig.configure(new ObjectMapper().registerModule(new JavaTimeModule()));

        Amounts back = roundTrip(mapper);

        assertThat(back.claimed()).isEqualTo(new BigDecimal("200000.00"));
        assertThat(back.largestColumnValue()).isEqualTo(new BigDecimal("9999999999999.99"));
        assertThat(back.small()).isEqualTo(new BigDecimal("0.10"));
        assertThat(back.eighteenDigits()).isEqualTo(new BigDecimal("1234567890123456.78"));
    }

    @Test
    void configuredMapperWritesPlainNotScientific() throws Exception {
        ObjectMapper mapper = JsonConfig.configure(new ObjectMapper());
        JsonNode tree = mapper.valueToTree(Map.of("amount", new BigDecimal("200000.00")));

        assertThat(mapper.writeValueAsString(tree)).isEqualTo("{\"amount\":200000.00}");
    }
}
