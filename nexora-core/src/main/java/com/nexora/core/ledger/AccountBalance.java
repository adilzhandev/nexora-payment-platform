package com.nexora.core.ledger;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountBalance(UUID accountId, String currency, BigDecimal balance) {}
