package com.nexora.core.payment;

import com.nexora.common.event.EventWriter;
import com.nexora.common.event.FinancialEvent;
import com.nexora.core.error.DomainException;
import com.nexora.core.idempotency.IdempotencyService;
import com.nexora.core.ledger.LedgerService;
import com.nexora.core.ledger.Money;
import com.nexora.core.provider.PaymentProviderSimulator;
import com.nexora.core.provider.ProviderResult;
import com.nexora.core.provider.ProviderScenario;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {
    private final PaymentRepository payments;
    private final PaymentHistoryRepository histories;
    private final IdempotencyService idempotency;
    private final LedgerService ledger;
    private final PaymentProviderSimulator provider;
    private final EventWriter events;

    public PaymentService(PaymentRepository payments, PaymentHistoryRepository histories,
                          IdempotencyService idempotency, LedgerService ledger,
                          PaymentProviderSimulator provider, EventWriter events) {
        this.payments = payments;
        this.histories = histories;
        this.idempotency = idempotency;
        this.ledger = ledger;
        this.provider = provider;
        this.events = events;
    }

    @Transactional
    public PaymentView create(UUID companyId, UUID actorId, String key, CreatePayment command, String correlationId) {
        CreatePayment normalized = normalize(command);
        return idempotency.execute(companyId, key, "CREATE_PAYMENT", normalized, PaymentView.class, () -> {
            Payment payment = payments.saveAndFlush(new Payment(companyId, normalized.amount(), normalized.currency(),
                    normalized.description(), normalized.scenario()));
            recordHistory(payment, actorId, "CREATED");
            publish(payment, "PaymentCreated", actorId, correlationId);
            return PaymentView.from(payment);
        });
    }

    @Transactional(readOnly = true)
    public PaymentView get(UUID companyId, UUID paymentId) {
        return PaymentView.from(payments.findByIdAndCompanyId(paymentId, companyId)
                .orElseThrow(() -> DomainException.notFound("Payment")));
    }

    @Transactional(readOnly = true)
    public Page<PaymentView> list(UUID companyId, PaymentStatus status, Pageable pageable) {
        Page<Payment> page = status == null ? payments.findByCompanyId(companyId, pageable)
                : payments.findByCompanyIdAndStatus(companyId, status, pageable);
        return page.map(PaymentView::from);
    }

    @Transactional
    public PaymentView confirm(UUID companyId, UUID actorId, UUID paymentId, String key, String correlationId) {
        return idempotency.execute(companyId, key, "CONFIRM_PAYMENT", paymentId, PaymentView.class, () -> {
            Payment payment = locked(companyId, paymentId);
            payment.transition(PaymentStatus.PROCESSING, null);
            recordHistory(payment, actorId, "CONFIRMATION_ACCEPTED");
            publish(payment, "PaymentProcessing", actorId, correlationId);
            ProviderResult result = provider.process(payment.getScenario());
            for (int notification = 0; notification < result.notifications(); notification++) {
                complete(payment, actorId, result, correlationId);
            }
            return PaymentView.from(payment);
        });
    }

    @Transactional
    public PaymentView cancel(UUID companyId, UUID actorId, UUID paymentId, String key, String correlationId) {
        return idempotency.execute(companyId, key, "CANCEL_PAYMENT", paymentId, PaymentView.class, () -> {
            Payment payment = locked(companyId, paymentId);
            payment.transition(PaymentStatus.CANCELLED, null);
            recordHistory(payment, actorId, "CANCELLED_BY_USER");
            publish(payment, "PaymentCancelled", actorId, correlationId);
            return PaymentView.from(payment);
        });
    }

    @Transactional(readOnly = true)
    public List<PaymentHistoryView> history(UUID companyId, UUID paymentId) {
        get(companyId, paymentId);
        return histories.findByPaymentIdOrderByOccurredAtAsc(paymentId).stream().map(PaymentHistoryView::from).toList();
    }

    private void complete(Payment payment, UUID actorId, ProviderResult result, String correlationId) {
        if (payment.getStatus() == PaymentStatus.SUCCEEDED || payment.getStatus() == PaymentStatus.FAILED) {
            return;
        }
        PaymentStatus status = result.succeeded() ? PaymentStatus.SUCCEEDED : PaymentStatus.FAILED;
        payment.transition(status, result.succeeded() ? null : result.code());
        if (result.succeeded()) {
            ledger.settle(payment.getCompanyId(), payment.getId(), payment.getAmount(), payment.getCurrency());
        }
        recordHistory(payment, actorId, result.code());
        publish(payment, result.succeeded() ? "PaymentSucceeded" : "PaymentFailed", actorId, correlationId);
    }

    private Payment locked(UUID companyId, UUID paymentId) {
        return payments.findLocked(paymentId, companyId).orElseThrow(() -> DomainException.notFound("Payment"));
    }

    private void recordHistory(Payment payment, UUID actorId, String reason) {
        histories.save(new PaymentHistory(payment.getId(), payment.getStatus(), actorId, reason));
    }

    private void publish(Payment payment, String eventType, UUID actorId, String correlationId) {
        events.append(new FinancialEvent(UUID.randomUUID(), eventType, payment.getId(), payment.getCompanyId(),
                Instant.now(), 1, correlationId, Map.of("status", payment.getStatus().name(), "amount", payment.getAmount(),
                "currency", payment.getCurrency(), "actorId", actorId.toString())));
    }

    private CreatePayment normalize(CreatePayment command) {
        if (command == null) {
            throw DomainException.invalid("Payment details are required");
        }
        String description = command.description() == null ? "" : command.description().trim();
        if (description.length() > 500) {
            throw DomainException.invalid("Payment description must be no longer than 500 characters");
        }
        return new CreatePayment(Money.positive(command.amount()), Money.currency(command.currency()), description,
                command.scenario() == null ? ProviderScenario.SUCCESS : command.scenario());
    }
}
