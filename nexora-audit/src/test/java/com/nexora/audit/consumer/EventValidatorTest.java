package com.nexora.audit.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexora.common.event.FinancialEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EventValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final EventValidator validator = new EventValidator(mapper);

    @Test
    void acceptsSupportedEnvelope() throws Exception {
        FinancialEvent event = event();
        assertThat(validator.read(mapper.writeValueAsString(event))).isEqualTo(event);
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventId", "entityId", "companyId", "eventType", "timestamp", "schemaVersion", "correlationId", "data"})
    void rejectsMissingFields(String field) throws Exception {
        ObjectNode payload = mapper.valueToTree(event());
        payload.remove(field);
        assertThatThrownBy(() -> validator.read(mapper.writeValueAsString(payload)))
                .isInstanceOf(InvalidEventException.class);
    }

    @Test
    void rejectsUnknownSchemaAndEventType() throws Exception {
        ObjectNode payload = mapper.valueToTree(event());
        payload.put("schemaVersion", 2);
        assertThatThrownBy(() -> validator.read(payload.toString())).isInstanceOf(InvalidEventException.class);
        payload.put("schemaVersion", 1).put("eventType", "PaymentErased");
        assertThatThrownBy(() -> validator.read(payload.toString())).isInstanceOf(InvalidEventException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "{broken", "{\"data\":[]}"})
    void rejectsMalformedEnvelope(String value) {
        assertThatThrownBy(() -> validator.read(value)).isInstanceOf(InvalidEventException.class);
    }

    private FinancialEvent event() {
        return new FinancialEvent(UUID.randomUUID(), "PaymentSucceeded", UUID.randomUUID(), UUID.randomUUID(),
                Instant.parse("2026-01-02T10:00:00Z"), 1, "request-123", Map.of("currency", "KZT"));
    }
}
