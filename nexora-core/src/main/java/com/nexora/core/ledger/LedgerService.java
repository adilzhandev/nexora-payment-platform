package com.nexora.core.ledger;

import com.nexora.core.error.DomainException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerService {
    private final JdbcTemplate jdbc;
    private final LedgerAccountRepository accounts;
    private final LedgerTransactionRepository transactions;

    public LedgerService(JdbcTemplate jdbc, LedgerAccountRepository accounts, LedgerTransactionRepository transactions) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.transactions = transactions;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void settle(UUID companyId, UUID paymentId, BigDecimal amount, String currency) {
        lockAccounts(companyId, currency);
        LedgerAccount clearing = account(companyId, currency, "PROVIDER_CLEARING");
        LedgerAccount merchant = account(companyId, currency, "MERCHANT_BALANCE");
        post(companyId, paymentId, null, amount, currency, "PAYMENT", clearing, merchant);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void refund(UUID companyId, UUID paymentId, UUID refundId, BigDecimal amount, String currency) {
        lockAccounts(companyId, currency);
        LedgerAccount merchant = account(companyId, currency, "MERCHANT_BALANCE");
        LedgerAccount clearing = account(companyId, currency, "PROVIDER_CLEARING");
        if (balance(merchant.getId()).compareTo(amount) < 0) {
            throw new DomainException("INSUFFICIENT_FUNDS", "Merchant balance is insufficient for this refund");
        }
        post(companyId, paymentId, refundId, amount, currency, "REFUND", merchant, clearing);
    }

    @Transactional(readOnly = true)
    public List<AccountBalance> balances(UUID companyId) {
        return jdbc.query("""
                select a.id, a.currency, coalesce(sum(case when e.side = 'CREDIT' then e.amount else -e.amount end), 0) balance
                from ledger_accounts a left join ledger_entries e on e.account_id = a.id
                where a.company_id = ? and a.account_type = 'MERCHANT_BALANCE'
                group by a.id, a.currency order by a.currency
                """, (rs, row) -> new AccountBalance(rs.getObject("id", UUID.class), rs.getString("currency"),
                rs.getBigDecimal("balance")), companyId);
    }

    @Transactional(readOnly = true)
    public Page<LedgerTransactionView> history(UUID companyId, Pageable pageable) {
        Page<LedgerTransaction> page = transactions.findByCompanyId(companyId, pageable);
        if (page.isEmpty()) {
            return page.map(LedgerTransactionView::from);
        }
        List<UUID> ids = page.stream().map(LedgerTransaction::getId).toList();
        Map<UUID, LedgerTransaction> fetched = transactions.findWithEntries(ids).stream()
                .collect(Collectors.toMap(LedgerTransaction::getId, Function.identity()));
        return page.map(transaction -> LedgerTransactionView.from(fetched.get(transaction.getId())));
    }

    private void post(UUID companyId, UUID paymentId, UUID refundId, BigDecimal amount, String currency,
                      String operation, LedgerAccount debit, LedgerAccount credit) {
        LedgerValidator.requireBalanced(List.of(new LedgerValidator.Posting(EntrySide.DEBIT, amount, currency),
                new LedgerValidator.Posting(EntrySide.CREDIT, amount, currency)));
        LedgerTransaction transaction = new LedgerTransaction(companyId, paymentId, refundId, currency, operation);
        transaction.addEntry(debit, EntrySide.DEBIT, amount);
        transaction.addEntry(credit, EntrySide.CREDIT, amount);
        transactions.saveAndFlush(transaction);
    }

    private void lockAccounts(UUID companyId, String currency) {
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class,
                companyId + ":ledger:" + currency);
    }

    private LedgerAccount account(UUID companyId, String currency, String accountType) {
        jdbc.update("insert into ledger_accounts (id, company_id, currency, account_type) values (?, ?, ?, ?) on conflict (company_id, currency, account_type) do nothing",
                UUID.randomUUID(), companyId, currency, accountType);
        UUID id = jdbc.queryForObject("select id from ledger_accounts where company_id = ? and currency = ? and account_type = ?",
                UUID.class, companyId, currency, accountType);
        return accounts.findById(id).orElseThrow(() -> new IllegalStateException("Ledger account is missing"));
    }

    private BigDecimal balance(UUID accountId) {
        return jdbc.queryForObject("select coalesce(sum(case when side = 'CREDIT' then amount else -amount end), 0) from ledger_entries where account_id = ?",
                BigDecimal.class, accountId);
    }
}
