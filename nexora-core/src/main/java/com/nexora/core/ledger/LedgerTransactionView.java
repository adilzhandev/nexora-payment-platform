package com.nexora.core.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LedgerTransactionView(UUID id, UUID paymentId, UUID refundId, String currency,
                                    String operation, Instant createdAt, List<Entry> entries) {
    public static LedgerTransactionView from(LedgerTransaction transaction) {
        return new LedgerTransactionView(transaction.getId(), transaction.getPaymentId(), transaction.getRefundId(),
                transaction.getCurrency(), transaction.getOperation(), transaction.getCreatedAt(),
                transaction.getEntries().stream().map(entry -> new Entry(entry.getId(), entry.getAccount().getId(),
                        entry.getAccount().getAccountType(), entry.getSide(), entry.getAmount())).toList());
    }

    public record Entry(UUID id, UUID accountId, String accountType, EntrySide side, BigDecimal amount) {}
}
