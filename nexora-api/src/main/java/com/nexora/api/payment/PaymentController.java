package com.nexora.api.payment;

import com.nexora.api.auth.CurrentUser;
import com.nexora.api.auth.TenantResolver;
import com.nexora.api.config.CorrelationFilter;
import com.nexora.core.payment.CreatePayment;
import com.nexora.core.payment.PaymentHistoryView;
import com.nexora.core.payment.PaymentService;
import com.nexora.core.payment.PaymentStatus;
import com.nexora.core.payment.PaymentView;
import com.nexora.core.provider.ProviderScenario;
import com.nexora.core.refund.RefundService;
import com.nexora.core.refund.RefundView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    private final PaymentService payments;
    private final RefundService refunds;
    private final TenantResolver tenants;

    public PaymentController(PaymentService payments, RefundService refunds, TenantResolver tenants) {
        this.payments = payments;
        this.refunds = refunds;
        this.tenants = tenants;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('MERCHANT', 'ADMIN')")
    public PaymentView create(@AuthenticationPrincipal CurrentUser user,
                              @RequestHeader(value = "X-Company-Id", required = false) UUID companyId,
                              @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
                              @RequestAttribute(CorrelationFilter.ATTRIBUTE) String correlationId,
                              @Valid @RequestBody NewPayment request) {
        return payments.create(tenants.resolve(user, companyId), user.id(), key,
                new CreatePayment(request.amount(), request.currency(), request.description(), request.scenario()), correlationId);
    }

    @GetMapping("/{id}")
    public PaymentView get(@AuthenticationPrincipal CurrentUser user,
                           @RequestHeader(value = "X-Company-Id", required = false) UUID companyId,
                           @PathVariable UUID id) {
        return payments.get(tenants.resolve(user, companyId), id);
    }

    @GetMapping
    public Page<PaymentView> list(@AuthenticationPrincipal CurrentUser user,
                                  @RequestHeader(value = "X-Company-Id", required = false) UUID companyId,
                                  @RequestParam(required = false) PaymentStatus status,
                                  @RequestParam(defaultValue = "0") @Min(0) int page,
                                  @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return payments.list(tenants.resolve(user, companyId), status,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAnyRole('MERCHANT', 'ADMIN')")
    public PaymentView confirm(@AuthenticationPrincipal CurrentUser user,
                               @RequestHeader(value = "X-Company-Id", required = false) UUID companyId,
                               @PathVariable UUID id,
                               @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
                               @RequestAttribute(CorrelationFilter.ATTRIBUTE) String correlationId) {
        return payments.confirm(tenants.resolve(user, companyId), user.id(), id, key, correlationId);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('MERCHANT', 'ADMIN')")
    public PaymentView cancel(@AuthenticationPrincipal CurrentUser user,
                              @RequestHeader(value = "X-Company-Id", required = false) UUID companyId,
                              @PathVariable UUID id,
                              @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
                              @RequestAttribute(CorrelationFilter.ATTRIBUTE) String correlationId) {
        return payments.cancel(tenants.resolve(user, companyId), user.id(), id, key, correlationId);
    }

    @GetMapping("/{id}/history")
    public List<PaymentHistoryView> history(@AuthenticationPrincipal CurrentUser user,
                                          @RequestHeader(value = "X-Company-Id", required = false) UUID companyId,
                                          @PathVariable UUID id) {
        return payments.history(tenants.resolve(user, companyId), id);
    }

    @PostMapping("/{id}/refunds")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('MERCHANT', 'ADMIN')")
    public RefundView refund(@AuthenticationPrincipal CurrentUser user,
                             @RequestHeader(value = "X-Company-Id", required = false) UUID companyId,
                             @PathVariable UUID id,
                             @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
                             @RequestAttribute(CorrelationFilter.ATTRIBUTE) String correlationId,
                             @Valid @RequestBody NewRefund request) {
        return refunds.refund(tenants.resolve(user, companyId), user.id(), id, key, request.amount(), correlationId);
    }

    @GetMapping("/{id}/refunds")
    public List<RefundView> refunds(@AuthenticationPrincipal CurrentUser user,
                                   @RequestHeader(value = "X-Company-Id", required = false) UUID companyId,
                                   @PathVariable UUID id) {
        return refunds.list(tenants.resolve(user, companyId), id);
    }

    public record NewPayment(@NotNull @DecimalMin("0.01") @Digits(integer = 16, fraction = 2) BigDecimal amount,
                              @NotBlank @Pattern(regexp = "KZT|USD|EUR") String currency,
                              @Size(max = 500) String description, ProviderScenario scenario) {}
    public record NewRefund(@DecimalMin("0.01") @Digits(integer = 16, fraction = 2) BigDecimal amount) {}
}
