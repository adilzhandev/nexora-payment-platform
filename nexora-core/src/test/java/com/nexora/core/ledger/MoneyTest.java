package com.nexora.core.ledger;

import com.nexora.core.error.DomainException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class MoneyTest {
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1.001", "1000000000000000.00"})
    void rejectsInvalidAmounts(String amount) {
        assertThatThrownBy(() -> Money.positive(new BigDecimal(amount))).isInstanceOf(DomainException.class);
    }

    @Test
    void normalizesExactlyWithoutRounding() {
        assertThat(Money.positive(new BigDecimal("1"))).isEqualTo(new BigDecimal("1.00"));
        assertThat(Money.positive(new BigDecimal("10.000"))).isEqualTo(new BigDecimal("10.00"));
    }

    @Test
    void rejectsUnsupportedCurrenciesAndMissingAmounts() {
        assertThatThrownBy(() -> Money.currency("JPY")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> Money.currency(null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> Money.positive(null)).isInstanceOf(DomainException.class);
    }
}
