package com.nexora.core.ledger;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "ledger_transactions")
public class LedgerTransaction {
    @Id private UUID id;
    @Column(nullable = false) private UUID companyId;
    @Column(nullable = false) private UUID paymentId;
    private UUID refundId;
    @Column(nullable = false, length = 3) private String currency;
    @Column(nullable = false, length = 20) private String operation;
    @Column(nullable = false) private Instant createdAt;
    @OneToMany(mappedBy = "transaction", cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    private List<LedgerEntry> entries = new ArrayList<>();

    protected LedgerTransaction() {}

    public LedgerTransaction(UUID companyId, UUID paymentId, UUID refundId, String currency, String operation) {
        this.id = UUID.randomUUID();
        this.companyId = companyId;
        this.paymentId = paymentId;
        this.refundId = refundId;
        this.currency = currency;
        this.operation = operation;
        this.createdAt = Instant.now();
    }

    public void addEntry(LedgerAccount account, EntrySide side, java.math.BigDecimal amount) {
        if (!companyId.equals(account.getCompanyId()) || !currency.equals(account.getCurrency())) {
            throw new IllegalArgumentException("Ledger account must belong to the transaction company and currency");
        }
        entries.add(new LedgerEntry(this, account, side, amount));
    }

    public UUID getId() { return id; }
    public UUID getPaymentId() { return paymentId; }
    public UUID getRefundId() { return refundId; }
    public String getCurrency() { return currency; }
    public String getOperation() { return operation; }
    public Instant getCreatedAt() { return createdAt; }
    public List<LedgerEntry> getEntries() { return List.copyOf(entries); }
}
