package com.nexora.api.payment;

import com.nexora.api.auth.AuthService;
import com.nexora.api.auth.CurrentUser;
import com.nexora.api.auth.TenantResolver;
import com.nexora.api.config.CorrelationFilter;
import com.nexora.api.config.SecurityConfiguration;
import com.nexora.api.error.ApiExceptionHandler;
import com.nexora.api.user.Role;
import com.nexora.core.payment.PaymentService;
import com.nexora.core.refund.RefundService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@ContextConfiguration(classes = {PaymentController.class, SecurityConfiguration.class,
        CorrelationFilter.class, ApiExceptionHandler.class})
class PaymentControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean AuthService auth;
    @MockitoBean PaymentService payments;
    @MockitoBean RefundService refunds;
    @MockitoBean TenantResolver tenants;

    @Test
    void anonymousRequestsReturnProblemAndCorrelationId() throws Exception {
        mvc.perform(get("/api/payments").header("X-Correlation-Id", "trace-42"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("status").value(401))
                .andExpect(jsonPath("correlationId").value("trace-42"))
                .andExpect(header().string("X-Correlation-Id", "trace-42"));
        verifyNoInteractions(payments);
    }

    @Test
    void auditorsCannotCreateFinancialOperations() throws Exception {
        authenticate(Role.AUDITOR);
        mvc.perform(post("/api/payments").header("Authorization", "Bearer token")
                        .header("Idempotency-Key", "payment-1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100.00,\"currency\":\"KZT\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(payments);
    }

    @Test
    void rejectsMoneyWithExcessivePrecision() throws Exception {
        authenticate(Role.MERCHANT);
        mvc.perform(post("/api/payments").header("Authorization", "Bearer token")
                        .header("Idempotency-Key", "payment-1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100.001,\"currency\":\"KZT\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("errors.amount").exists());
        verifyNoInteractions(payments);
    }

    @Test
    void mutationRequiresIdempotencyKey() throws Exception {
        authenticate(Role.MERCHANT);
        mvc.perform(post("/api/payments/" + UUID.randomUUID() + "/confirm")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(payments);
    }

    @Test
    void capsPageSize() throws Exception {
        authenticate(Role.MERCHANT);
        mvc.perform(get("/api/payments?size=1000").header("Authorization", "Bearer token"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(payments);
    }

    @Test
    void invalidCorrelationIdIsReplaced() throws Exception {
        mvc.perform(get("/api/payments").header("X-Correlation-Id", "not a valid trace"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Correlation-Id", org.hamcrest.Matchers.matchesPattern("[0-9a-f-]{36}")));
    }

    private void authenticate(Role role) {
        var user = new CurrentUser(UUID.randomUUID(), UUID.randomUUID(), "merchant@example.test", role);
        when(auth.authenticate("token")).thenReturn(Optional.of(user));
        when(tenants.resolve(any(), any())).thenReturn(user.companyId());
    }
}
