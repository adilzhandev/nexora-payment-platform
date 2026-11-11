package com.nexora.core.payment;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "payment_history")
public class PaymentHistory {
    @Id private UUID id;
    @Column(nullable = false) private UUID paymentId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private PaymentStatus status;
    @Column(nullable = false) private UUID actorId;
    @Column(nullable = false, length = 80) private String reason;
    @Column(nullable = false) private Instant occurredAt;

    protected PaymentHistory() {}

    public PaymentHistory(UUID paymentId, PaymentStatus status, UUID actorId, String reason) {
        this.id = UUID.randomUUID();
        this.paymentId = paymentId;
        this.status = status;
        this.actorId = actorId;
        this.reason = reason;
        this.occurredAt = Instant.now();
    }

    public UUID getId() { return id; }
    public PaymentStatus getStatus() { return status; }
    public UUID getActorId() { return actorId; }
    public String getReason() { return reason; }
    public Instant getOccurredAt() { return occurredAt; }
}
