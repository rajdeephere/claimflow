package com.claimflow.common.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Money-safe JSON (BUG-009, ADR-0010). Jackson's defaults silently change decimals that pass through a
 * JsonNode tree, which every Kafka event payload does:
 * <ul>
 *   <li>building a tree: {@code BigDecimal} trailing zeros are stripped (200000.00 becomes 2E+5)</li>
 *   <li>writing: such values come out in scientific notation ({@code "claimedAmount":2E+5})</li>
 *   <li>reading: decimals are parsed as {@code double}: the scale is lost (200000.00 becomes 200000.0), and
 *       precision too beyond ~17 significant digits (our NUMERIC(15,2) stays within that, but
 *       other producers or currencies may not)</li>
 * </ul>
 */
@Configuration
public class JsonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer moneySafeJson() {
        return builder -> builder
                .featuresToEnable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                        JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
                .postConfigurer(JsonConfig::useExactDecimalNodes);
    }

    /** Applies the same settings to a hand-built mapper (used by tests and non-Spring code). */
    public static ObjectMapper configure(ObjectMapper mapper) {
        mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        mapper.enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);
        useExactDecimalNodes(mapper);
        return mapper;
    }

    private static void useExactDecimalNodes(ObjectMapper mapper) {
        mapper.setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
    }
}
