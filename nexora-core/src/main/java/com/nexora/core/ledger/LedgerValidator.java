package com.nexora.core.ledger;

import com.nexora.core.error.DomainException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class LedgerValidator {
    private LedgerValidator() {}

    public static void requireBalanced(List<Posting> postings) {
        if (postings.size() < 2) {
            throw DomainException.invalid("A ledger transaction needs at least two entries");
        }
        Map<String, BigDecimal> balances = new HashMap<>();
        for (Posting posting : postings) {
            String currency = Money.currency(posting.currency());
            BigDecimal amount = Money.positive(posting.amount());
            if (posting.side() == null) {
                throw DomainException.invalid("Ledger entry side is required");
            }
            BigDecimal signed = posting.side() == EntrySide.DEBIT ? amount : amount.negate();
            balances.merge(currency, signed, BigDecimal::add);
        }
        if (balances.values().stream().anyMatch(balance -> balance.signum() != 0)) {
            throw DomainException.invalid("Debits and credits must balance separately in every currency");
        }
    }

    public record Posting(EntrySide side, BigDecimal amount, String currency) {}
}
