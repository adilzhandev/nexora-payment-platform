package com.nexora.core.payment;

import java.time.Instant;
import java.util.UUID;

public record PaymentHistoryView(UUID id, PaymentStatus status, UUID actorId, String reason, Instant occurredAt) {
    public static PaymentHistoryView from(PaymentHistory history) {
        return new PaymentHistoryView(history.getId(), history.getStatus(), history.getActorId(),
                history.getReason(), history.getOccurredAt());
    }
}
