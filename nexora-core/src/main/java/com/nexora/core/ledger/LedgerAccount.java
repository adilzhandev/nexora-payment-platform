package com.nexora.core.ledger;

import jakarta.persistence.*;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "ledger_accounts")
public class LedgerAccount {
    @Id private UUID id;
    @Column(nullable = false) private UUID companyId;
    @Column(nullable = false, length = 3) private String currency;
    @Column(nullable = false, length = 30) private String accountType;

    protected LedgerAccount() {}

    public UUID getId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public String getCurrency() { return currency; }
    public String getAccountType() { return accountType; }
}
