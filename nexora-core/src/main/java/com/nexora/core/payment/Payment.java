package com.nexora.core.payment;

import com.nexora.core.error.DomainException;
import com.nexora.core.ledger.Money;
import com.nexora.core.provider.ProviderScenario;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {
    @Id private UUID id;
    @Column(nullable = false) private UUID companyId;
    @Column(nullable = false, precision = 19, scale = 2) private BigDecimal amount;
    @Column(nullable = false, length = 3) private String currency;
    @Column(nullable = false, length = 500) private String description;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private PaymentStatus status;
    @Column(nullable = false, precision = 19, scale = 2) private BigDecimal refundedAmount;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private ProviderScenario scenario;
    @Column(length = 80) private String failureReason;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;
    @Version private long version;

    protected Payment() {}

    public Payment(UUID companyId, BigDecimal amount, String currency, String description, ProviderScenario scenario) {
        this.id = UUID.randomUUID();
        this.companyId = companyId;
        this.amount = amount;
        this.currency = currency;
        this.description = description;
        this.scenario = scenario;
        this.status = PaymentStatus.CREATED;
        this.refundedAmount = new BigDecimal("0.00");
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public void transition(PaymentStatus target, String failureReason) {
        status.requireTransitionTo(target);
        status = target;
        this.failureReason = failureReason;
        updatedAt = Instant.now();
    }

    public void addRefund(BigDecimal refundAmount) {
        refundAmount = Money.positive(refundAmount);
        if (status != PaymentStatus.SUCCEEDED) {
            throw DomainException.conflict("Only succeeded payments can be refunded");
        }
        if (refundedAmount.add(refundAmount).compareTo(amount) > 0) {
            throw DomainException.conflict("Refund exceeds the remaining refundable amount");
        }
        refundedAmount = refundedAmount.add(refundAmount);
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getDescription() { return description; }
    public PaymentStatus getStatus() { return status; }
    public BigDecimal getRefundedAmount() { return refundedAmount; }
    public ProviderScenario getScenario() { return scenario; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
