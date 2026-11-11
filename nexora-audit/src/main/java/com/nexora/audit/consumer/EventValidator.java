package com.nexora.audit.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.common.event.FinancialEvent;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class EventValidator {
    private static final Set<String> EVENT_TYPES = Set.of("PaymentCreated", "PaymentProcessing",
            "PaymentSucceeded", "PaymentFailed", "PaymentCancelled", "RefundCompleted");
    private final ObjectMapper mapper;

    public EventValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public FinancialEvent read(String payload) {
        if (payload == null || payload.length() > 262_144) {
            throw new InvalidEventException("Event payload is empty or too large");
        }
        FinancialEvent event;
        try {
            var document = mapper.readTree(payload);
            if (document == null || !document.isObject() || !document.path("data").isObject()) {
                throw new InvalidEventException("Event must contain a data object");
            }
            event = mapper.readValue(payload, FinancialEvent.class);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new InvalidEventException("Event payload is not valid JSON", exception);
        }
        if (event == null || event.eventId() == null || event.entityId() == null
                || event.companyId() == null || event.timestamp() == null
                || event.correlationId() == null || event.correlationId().isBlank()
                || event.correlationId().length() > 128 || event.data() == null) {
            throw new InvalidEventException("Event envelope has missing or invalid fields");
        }
        if (event.schemaVersion() != 1 || event.eventType() == null || !EVENT_TYPES.contains(event.eventType())) {
            throw new InvalidEventException("Event type or schema version is unsupported");
        }
        return event;
    }
}
