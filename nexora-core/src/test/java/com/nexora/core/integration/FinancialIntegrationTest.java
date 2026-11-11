package com.nexora.core.integration;

import com.nexora.common.event.EventWriter;
import com.nexora.core.error.DomainException;
import com.nexora.core.idempotency.IdempotencyService;
import com.nexora.core.ledger.*;
import com.nexora.core.payment.*;
import com.nexora.core.provider.PaymentProviderSimulator;
import com.nexora.core.provider.ProviderScenario;
import com.nexora.core.refund.RefundService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("integration")
@Testcontainers
@SpringBootTest(classes = FinancialIntegrationTest.TestApplication.class, properties = {
        "spring.liquibase.change-log=classpath:db/core.sql", "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false", "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.stat=OFF", "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"
})
class FinancialIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired PaymentService payments;
    @Autowired RefundService refunds;
    @Autowired LedgerService ledger;
    @Autowired JdbcTemplate jdbc;
    @Autowired LedgerTransactionRepository ledgerTransactions;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @MockitoBean EventWriter events;
    private UUID companyId;
    private UUID actorId;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("truncate ledger_entries, ledger_transactions, ledger_accounts, refunds, payment_history, idempotency_records, payments cascade");
        companyId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        reset(events);
    }

    @Test
    void repeatedCreationReturnsTheOriginalSnapshotAndRejectsChangedPayload() {
        CreatePayment command = command("100.00", ProviderScenario.SUCCESS);
        PaymentView created = payments.create(companyId, actorId, "same-key", command, "correlation");
        payments.confirm(companyId, actorId, created.id(), "confirm", "correlation");
        PaymentView repeated = payments.create(companyId, actorId, "same-key", command, "other-correlation");
        assertThat(repeated).isEqualTo(created);
        assertThat(repeated.status()).isEqualTo(PaymentStatus.CREATED);
        assertThat(payments.get(companyId, created.id()).status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThatThrownBy(() -> payments.create(companyId, actorId, "same-key", command("101", ProviderScenario.SUCCESS), "correlation"))
                .isInstanceOf(DomainException.class).hasMessageContaining("different request");
        assertThat(count("payments")).isEqualTo(1);
        assertThat(count("ledger_transactions")).isEqualTo(1);
    }

    @Test
    void partialAndFullRefundsCreateCompensatingBalancedTransactions() {
        PaymentView payment = settled("10000", ProviderScenario.SUCCESS);
        var partial = refunds.refund(companyId, actorId, payment.id(), "partial", new BigDecimal("3000"), "correlation");
        var partialReplay = refunds.refund(companyId, actorId, payment.id(), "partial", new BigDecimal("3000.00"), "correlation");
        assertThat(partialReplay).isEqualTo(partial);
        assertThat(ledger.balances(companyId).getFirst().balance()).isEqualByComparingTo("7000");
        var remaining = refunds.refund(companyId, actorId, payment.id(), "remaining", null, "correlation");
        assertThat(remaining.amount()).isEqualByComparingTo("7000");
        assertThat(ledger.balances(companyId).getFirst().balance()).isZero();
        assertThat(payments.get(companyId, payment.id()).refundedAmount()).isEqualByComparingTo("10000");
        assertThat(refunds.list(companyId, payment.id())).hasSize(2);
        assertThatThrownBy(() -> refunds.refund(companyId, actorId, payment.id(), "too-much", BigDecimal.ONE, "correlation"))
                .isInstanceOf(DomainException.class);
        assertThat(count("ledger_transactions")).isEqualTo(3);
        assertThat(count("ledger_entries")).isEqualTo(6);
        assertThat(jdbc.queryForObject("select sum(case when side = 'DEBIT' then amount else -amount end) from ledger_entries", BigDecimal.class)).isZero();
    }

    @Test
    void duplicateNotificationsAndConfirmationRetriesNeverPostTwice() {
        PaymentView created = create("80", ProviderScenario.DUPLICATE_NOTIFICATION);
        PaymentView confirmed = payments.confirm(companyId, actorId, created.id(), "confirm", "correlation");
        assertThat(payments.confirm(companyId, actorId, created.id(), "confirm", "correlation")).isEqualTo(confirmed);
        assertThatThrownBy(() -> payments.confirm(companyId, actorId, created.id(), "new-key", "correlation"))
                .isInstanceOf(DomainException.class);
        assertThat(count("ledger_transactions")).isEqualTo(1);
        assertThat(payments.history(companyId, created.id())).extracting(PaymentHistoryView::status)
                .containsExactly(PaymentStatus.CREATED, PaymentStatus.PROCESSING, PaymentStatus.SUCCEEDED);
        verify(events, times(3)).append(any());
    }

    @Test
    void cancelledAndFailedPaymentsCannotBeSettledOrRefunded() {
        PaymentView created = create("20", ProviderScenario.SUCCESS);
        assertThat(payments.cancel(companyId, actorId, created.id(), "cancel", "correlation").status()).isEqualTo(PaymentStatus.CANCELLED);
        assertThatThrownBy(() -> payments.confirm(companyId, actorId, created.id(), "confirm", "correlation"))
                .isInstanceOf(DomainException.class);
        for (ProviderScenario scenario : List.of(ProviderScenario.DECLINED, ProviderScenario.TIMEOUT, ProviderScenario.NETWORK_ERROR)) {
            PaymentView failed = settled("20", scenario);
            assertThat(failed.status()).isEqualTo(PaymentStatus.FAILED);
            assertThat(failed.failureReason()).isNotBlank();
            assertThatThrownBy(() -> refunds.refund(companyId, actorId, failed.id(), UUID.randomUUID().toString(), null, "correlation"))
                    .isInstanceOf(DomainException.class);
        }
        assertThat(count("ledger_entries")).isZero();
    }

    @Test
    void tenantCannotReadOrMutateAnotherCompanyPayment() {
        PaymentView payment = settled("100", ProviderScenario.SUCCESS);
        UUID otherCompany = UUID.randomUUID();
        assertThatThrownBy(() -> payments.get(otherCompany, payment.id())).isInstanceOf(DomainException.class).hasMessageContaining("not found");
        assertThatThrownBy(() -> payments.confirm(otherCompany, actorId, payment.id(), "confirm", "correlation")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> refunds.refund(otherCompany, actorId, payment.id(), "refund", BigDecimal.ONE, "correlation")).isInstanceOf(DomainException.class);
        assertThat(payments.list(otherCompany, null, PageRequest.of(0, 20))).isEmpty();
        assertThat(ledger.balances(otherCompany)).isEmpty();
    }

    @Test
    void concurrentIdenticalRequestsCreateOnePayment() throws Exception {
        List<UUID> results = concurrently(12, () -> payments.create(companyId, actorId, "shared", command("90", ProviderScenario.SUCCESS), "correlation").id());
        assertThat(results).containsOnly(results.getFirst());
        assertThat(count("payments")).isEqualTo(1);
        assertThat(count("idempotency_records")).isEqualTo(1);
    }

    @Test
    void concurrentConfirmationsSettleExactlyOnce() throws Exception {
        PaymentView payment = create("100", ProviderScenario.DELAYED_SUCCESS);
        List<Boolean> results = concurrently(8, () -> {
            try {
                payments.confirm(companyId, actorId, payment.id(), UUID.randomUUID().toString(), "correlation");
                return true;
            } catch (DomainException exception) {
                assertThat(exception.getCode()).isEqualTo("CONFLICT");
                return false;
            }
        });
        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        assertThat(count("ledger_transactions")).isEqualTo(1);
        assertThat(ledger.balances(companyId).getFirst().balance()).isEqualByComparingTo("100");
    }

    @Test
    void concurrentRefundsCannotExceedPaymentAmount() throws Exception {
        PaymentView payment = settled("10000", ProviderScenario.SUCCESS);
        List<Boolean> results = concurrently(8, () -> {
            try {
                refunds.refund(companyId, actorId, payment.id(), UUID.randomUUID().toString(), new BigDecimal("2000"), "correlation");
                return true;
            } catch (DomainException exception) {
                assertThat(exception.getCode()).isEqualTo("CONFLICT");
                return false;
            }
        });
        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(5);
        assertThat(refunds.list(companyId, payment.id())).hasSize(5);
        assertThat(payments.get(companyId, payment.id()).refundedAmount()).isEqualByComparingTo("10000");
        assertThat(ledger.balances(companyId).getFirst().balance()).isZero();
    }

    @Test
    void databaseRejectsUnbalancedTransactionsAndMutationsOfPostedEntries() {
        PaymentView payment = settled("100", ProviderScenario.SUCCESS);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        UUID createdOnly = create("50", ProviderScenario.SUCCESS).id();
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> jdbc.update(
                "insert into ledger_transactions (id, company_id, payment_id, currency, operation, created_at) values (?, ?, ?, 'KZT', 'PAYMENT', now())",
                UUID.randomUUID(), companyId, createdOnly))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("update ledger_entries set amount = amount + 1")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("delete from ledger_entries")).isInstanceOf(RuntimeException.class);
        UUID ledgerId = jdbc.queryForObject("select id from ledger_transactions where payment_id = ?", UUID.class, payment.id());
        UUID accountId = jdbc.queryForObject("select id from ledger_accounts where account_type = 'MERCHANT_BALANCE'", UUID.class);
        assertThatThrownBy(() -> jdbc.update("insert into ledger_entries (id, transaction_id, account_id, side, amount, currency) values (?, ?, ?, 'CREDIT', 1, 'KZT')",
                UUID.randomUUID(), ledgerId, accountId)).isInstanceOf(RuntimeException.class);
        assertThat(count("ledger_transactions")).isEqualTo(1);
        assertThat(ledger.balances(companyId).getFirst().balance()).isEqualByComparingTo("100");
    }

    @Test
    void publicationFailureRollsBackPaymentAndLedgerInSameTransaction() {
        PaymentView payment = create("100", ProviderScenario.SUCCESS);
        doThrow(new IllegalStateException("Outbox unavailable")).when(events).append(any());
        assertThatThrownBy(() -> payments.confirm(companyId, actorId, payment.id(), "confirm", "correlation"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(payments.get(companyId, payment.id()).status()).isEqualTo(PaymentStatus.CREATED);
        assertThat(count("ledger_transactions")).isZero();
        assertThat(count("idempotency_records")).isEqualTo(1);
    }

    @Test
    void joinFetchRemovesNPlusOneWhilePreservingDatabasePagination() {
        for (int index = 0; index < 4; index++) {
            settled("100", ProviderScenario.SUCCESS);
        }
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        var page = PageRequest.of(0, 3, Sort.by("createdAt").descending());
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        long naiveQueries = transaction.execute(status -> {
            entityManager.clear();
            statistics.clear();
            ledgerTransactions.findByCompanyId(companyId, page).forEach(item ->
                    item.getEntries().forEach(entry -> entry.getAccount().getAccountType()));
            return statistics.getPrepareStatementCount();
        });
        long fetchedQueries = transaction.execute(status -> {
            entityManager.clear();
            statistics.clear();
            var history = ledger.history(companyId, page);
            assertThat(history).hasSize(3);
            assertThat(history.getTotalElements()).isEqualTo(4);
            assertThat(history.getContent()).allSatisfy(item -> assertThat(item.entries()).hasSize(2));
            return statistics.getPrepareStatementCount();
        });
        assertThat(naiveQueries).isGreaterThan(fetchedQueries);
        assertThat(fetchedQueries).isLessThanOrEqualTo(3);
    }

    private PaymentView create(String amount, ProviderScenario scenario) {
        return payments.create(companyId, actorId, UUID.randomUUID().toString(), command(amount, scenario), "correlation");
    }

    private PaymentView settled(String amount, ProviderScenario scenario) {
        PaymentView created = create(amount, scenario);
        return payments.confirm(companyId, actorId, created.id(), UUID.randomUUID().toString(), "correlation");
    }

    private CreatePayment command(String amount, ProviderScenario scenario) {
        return new CreatePayment(new BigDecimal(amount), "KZT", "Virtual payment", scenario);
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    private <T> List<T> concurrently(int count, Callable<T> action) throws Exception {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(count)) {
            List<Future<T>> futures = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent request barrier timed out");
                    }
                    return action.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("com.nexora.core")
    @EnableJpaRepositories("com.nexora.core")
    @Import({PaymentService.class, RefundService.class, LedgerService.class, IdempotencyService.class, PaymentProviderSimulator.class})
    static class TestApplication {}
}
