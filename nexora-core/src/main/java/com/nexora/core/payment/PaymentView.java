package com.nexora.core.payment;

import com.nexora.core.provider.ProviderScenario;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentView(UUID id, UUID companyId, BigDecimal amount, String currency,
                          String description, PaymentStatus status, BigDecimal refundedAmount,
                          ProviderScenario scenario, String failureReason, Instant createdAt, Instant updatedAt) {
    public static PaymentView from(Payment payment) {
        return new PaymentView(payment.getId(), payment.getCompanyId(), payment.getAmount(),
                payment.getCurrency(), payment.getDescription(), payment.getStatus(), payment.getRefundedAmount(),
                payment.getScenario(), payment.getFailureReason(), payment.getCreatedAt(), payment.getUpdatedAt());
    }
}
