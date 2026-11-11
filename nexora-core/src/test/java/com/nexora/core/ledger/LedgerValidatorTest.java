package com.nexora.core.ledger;

import com.nexora.core.error.DomainException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class LedgerValidatorTest {
    @Test
    void acceptsBalancedEntries() {
        assertThatCode(() -> LedgerValidator.requireBalanced(List.of(
                posting(EntrySide.DEBIT, "100.00", "KZT"), posting(EntrySide.CREDIT, "100.00", "KZT"))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnbalancedEntries() {
        assertThatThrownBy(() -> LedgerValidator.requireBalanced(List.of(
                posting(EntrySide.DEBIT, "100.00", "KZT"), posting(EntrySide.CREDIT, "99.99", "KZT"))))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void neverBalancesDifferentCurrenciesAgainstEachOther() {
        assertThatThrownBy(() -> LedgerValidator.requireBalanced(List.of(
                posting(EntrySide.DEBIT, "100.00", "USD"), posting(EntrySide.CREDIT, "100.00", "EUR"))))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsEmptyOrNonPositivePostings() {
        assertThatThrownBy(() -> LedgerValidator.requireBalanced(List.of())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> LedgerValidator.requireBalanced(List.of(
                posting(EntrySide.DEBIT, "-1", "KZT"), posting(EntrySide.CREDIT, "-1", "KZT"))))
                .isInstanceOf(DomainException.class);
    }

    private LedgerValidator.Posting posting(EntrySide side, String amount, String currency) {
        return new LedgerValidator.Posting(side, new BigDecimal(amount), currency);
    }
}
