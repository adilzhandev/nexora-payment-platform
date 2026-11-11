package com.nexora.api.payment;

import com.nexora.api.auth.CurrentUser;
import com.nexora.api.auth.TenantResolver;
import com.nexora.core.ledger.AccountBalance;
import com.nexora.core.ledger.LedgerService;
import com.nexora.core.ledger.LedgerTransactionView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ledger")
public class LedgerController {
    private final LedgerService ledger;
    private final TenantResolver tenants;

    public LedgerController(LedgerService ledger, TenantResolver tenants) {
        this.ledger = ledger;
        this.tenants = tenants;
    }

    @GetMapping("/balances")
    public List<AccountBalance> balances(@AuthenticationPrincipal CurrentUser user,
                                         @RequestHeader(value = "X-Company-Id", required = false) UUID companyId) {
        return ledger.balances(tenants.resolve(user, companyId));
    }

    @GetMapping("/transactions")
    public Page<LedgerTransactionView> history(@AuthenticationPrincipal CurrentUser user,
                                              @RequestHeader(value = "X-Company-Id", required = false) UUID companyId,
                                              @RequestParam(defaultValue = "0") @Min(0) int page,
                                              @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ledger.history(tenants.resolve(user, companyId),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
    }
}
