package com.nexora.core.refund;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "refunds")
public class Refund {
    @Id private UUID id;
    @Column(nullable = false) private UUID companyId;
    @Column(nullable = false) private UUID paymentId;
    @Column(nullable = false) private UUID actorId;
    @Column(nullable = false, precision = 19, scale = 2) private BigDecimal amount;
    @Column(nullable = false, length = 3) private String currency;
    @Column(nullable = false) private Instant createdAt;

    protected Refund() {}

    public Refund(UUID companyId, UUID paymentId, UUID actorId, BigDecimal amount, String currency) {
        this.id = UUID.randomUUID();
        this.companyId = companyId;
        this.paymentId = paymentId;
        this.actorId = actorId;
        this.amount = amount;
        this.currency = currency;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public UUID getPaymentId() { return paymentId; }
    public UUID getActorId() { return actorId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
}
