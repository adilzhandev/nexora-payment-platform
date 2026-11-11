package com.nexora.common.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record FinancialEvent(
        UUID eventId,
        String eventType,
        UUID entityId,
        UUID companyId,
        Instant timestamp,
        int schemaVersion,
        String correlationId,
        Map<String, Object> data) {
    public FinancialEvent {
        data = data == null ? Map.of() : Map.copyOf(data);
    }
}
