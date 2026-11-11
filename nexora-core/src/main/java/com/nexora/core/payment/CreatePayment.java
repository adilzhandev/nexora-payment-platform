package com.nexora.core.payment;

import com.nexora.core.provider.ProviderScenario;
import java.math.BigDecimal;

public record CreatePayment(BigDecimal amount, String currency, String description, ProviderScenario scenario) {}
