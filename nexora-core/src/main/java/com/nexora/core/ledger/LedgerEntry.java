package com.nexora.core.ledger;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "ledger_entries")
public class LedgerEntry {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false) private LedgerTransaction transaction;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false) private LedgerAccount account;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 6) private EntrySide side;
    @Column(nullable = false, precision = 19, scale = 2) private BigDecimal amount;
    @Column(nullable = false, length = 3) private String currency;

    protected LedgerEntry() {}

    public LedgerEntry(LedgerTransaction transaction, LedgerAccount account, EntrySide side, BigDecimal amount) {
        this.id = UUID.randomUUID();
        this.transaction = transaction;
        this.account = account;
        this.side = side;
        this.amount = Money.positive(amount);
        this.currency = account.getCurrency();
    }

    public UUID getId() { return id; }
    public LedgerAccount getAccount() { return account; }
    public EntrySide getSide() { return side; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
}
