package com.nexora.core.refund;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RefundView(UUID id, UUID paymentId, BigDecimal amount, String currency, UUID actorId, Instant createdAt) {
    public static RefundView from(Refund refund) {
        return new RefundView(refund.getId(), refund.getPaymentId(), refund.getAmount(), refund.getCurrency(),
                refund.getActorId(), refund.getCreatedAt());
    }
}
