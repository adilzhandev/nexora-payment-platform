package com.nexora.core.ledger;

import com.nexora.core.error.DomainException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

public final class Money {
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999999.99");
    private static final Set<String> CURRENCIES = Set.of("KZT", "USD", "EUR");

    private Money() {}

    public static BigDecimal positive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.compareTo(MAX_AMOUNT) > 0) {
            throw DomainException.invalid("Amount must be positive and no greater than " + MAX_AMOUNT);
        }
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw DomainException.invalid("Amount must contain at most two decimal places");
        }
    }

    public static String currency(String currency) {
        if (!CURRENCIES.contains(currency == null ? "" : currency)) {
            throw DomainException.invalid("Currency must be KZT, USD or EUR");
        }
        return currency;
    }
}
