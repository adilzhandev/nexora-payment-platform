package com.nexora.core.refund;

import com.nexora.common.event.EventWriter;
import com.nexora.common.event.FinancialEvent;
import com.nexora.core.error.DomainException;
import com.nexora.core.idempotency.IdempotencyService;
import com.nexora.core.ledger.LedgerService;
import com.nexora.core.ledger.Money;
import com.nexora.core.payment.Payment;
import com.nexora.core.payment.PaymentRepository;
import com.nexora.core.payment.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefundService {
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final IdempotencyService idempotency;
    private final LedgerService ledger;
    private final EventWriter events;

    public RefundService(PaymentRepository payments, RefundRepository refunds, IdempotencyService idempotency,
                         LedgerService ledger, EventWriter events) {
        this.payments = payments;
        this.refunds = refunds;
        this.idempotency = idempotency;
        this.ledger = ledger;
        this.events = events;
    }

    @Transactional
    public RefundView refund(UUID companyId, UUID actorId, UUID paymentId, String key, BigDecimal amount, String correlationId) {
        RefundRequest request = new RefundRequest(paymentId, amount == null ? null : Money.positive(amount));
        return idempotency.execute(companyId, key, "REFUND_PAYMENT", request, RefundView.class, () -> {
            Payment payment = payments.findLocked(paymentId, companyId).orElseThrow(() -> DomainException.notFound("Payment"));
            if (payment.getStatus() != PaymentStatus.SUCCEEDED) {
                throw DomainException.conflict("Only succeeded payments can be refunded");
            }
            BigDecimal refundAmount = request.amount() == null
                    ? payment.getAmount().subtract(payment.getRefundedAmount()) : request.amount();
            if (refundAmount.signum() == 0) {
                throw DomainException.conflict("Payment has already been fully refunded");
            }
            payment.addRefund(refundAmount);
            Refund refund = refunds.saveAndFlush(new Refund(companyId, paymentId, actorId, refundAmount, payment.getCurrency()));
            ledger.refund(companyId, paymentId, refund.getId(), refundAmount, payment.getCurrency());
            events.append(new FinancialEvent(UUID.randomUUID(), "RefundCompleted", refund.getId(), companyId, Instant.now(),
                    1, correlationId, Map.of("paymentId", paymentId.toString(), "amount", refundAmount,
                    "currency", payment.getCurrency(), "actorId", actorId.toString())));
            return RefundView.from(refund);
        });
    }

    @Transactional(readOnly = true)
    public List<RefundView> list(UUID companyId, UUID paymentId) {
        if (payments.findByIdAndCompanyId(paymentId, companyId).isEmpty()) {
            throw DomainException.notFound("Payment");
        }
        return refunds.findByPaymentIdAndCompanyIdOrderByCreatedAtAsc(paymentId, companyId).stream().map(RefundView::from).toList();
    }

    private record RefundRequest(UUID paymentId, BigDecimal amount) {}
}
