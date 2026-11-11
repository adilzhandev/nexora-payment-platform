package com.nexora.api.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.api.NexoraApplication;
import com.nexora.api.company.CompanyCache;
import com.nexora.api.company.CompanyView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@Tag("integration")
@Testcontainers
@SpringBootTest(classes = NexoraApplication.class, properties = {
        "nexora.outbox.enabled=false", "spring.kafka.admin.auto-create=false",
        "nexora.bootstrap.email=admin@nexora.test", "nexora.bootstrap.password=Test-admin-password-42",
        "logging.level.root=WARN"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PaymentApiIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
        properties.add("spring.data.redis.host", redis::getHost);
        properties.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired MockMvc http;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate database;
    @Autowired StringRedisTemplate cache;
    @MockitoSpyBean CompanyCache companyCache;

    @Test
    void paymentAndRefundLifecycleReplaysExactResponses() throws Exception {
        Customer customer = customer();
        String key = UUID.randomUUID().toString();
        Map<String, Object> request = paymentRequest("100.00");
        JsonNode created = json(send(post("/api/payments"), customer.token(), key, request, 201));
        String id = created.path("id").asText();
        assertThat(json(send(post("/api/payments"), customer.token(), key, request, 201))).isEqualTo(created);
        send(post("/api/payments"), customer.token(), key, paymentRequest("101.00"), 409);
        JsonNode succeeded = json(send(post("/api/payments/{id}/confirm", id), customer.token(), "confirm", null, 200));
        assertThat(succeeded.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(json(send(post("/api/payments/{id}/confirm", id), customer.token(), "confirm", null, 200)))
                .isEqualTo(succeeded);
        send(post("/api/payments/{id}/confirm", id), customer.token(), "another-confirm", null, 409);
        JsonNode refund = json(send(post("/api/payments/{id}/refunds", id), customer.token(), "refund-1",
                Map.of("amount", "30.00"), 201));
        assertThat(json(send(post("/api/payments/{id}/refunds", id), customer.token(), "refund-1",
                Map.of("amount", "30.00"), 201))).isEqualTo(refund);
        send(post("/api/payments/{id}/refunds", id), customer.token(), "refund-too-large", Map.of("amount", "70.01"), 409);
        send(post("/api/payments/{id}/refunds", id), customer.token(), "refund-remainder", Map.of(), 201);
        JsonNode payment = json(send(get("/api/payments/{id}", id), customer.token(), null, null, 200));
        assertThat(payment.path("refundedAmount").decimalValue()).isEqualByComparingTo("100.00");
        JsonNode history = json(send(get("/api/payments/{id}/history", id), customer.token(), null, null, 200));
        assertThat(history).hasSize(3);
        assertThat(json(send(get("/api/payments/{id}/refunds", id), customer.token(), null, null, 200))).hasSize(2);
        assertThat(json(send(get("/api/ledger/transactions"), customer.token(), null, null, 200))
                .path("totalElements").asInt()).isEqualTo(3);
        assertThat(json(send(get("/api/ledger/balances"), customer.token(), null, null, 200)).get(0)
                .path("balance").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(json(send(post("/api/payments"), customer.token(), key, request, 201))).isEqualTo(created);
        assertThat(database.queryForObject("select count(*) from outbox_events where entity_id=?", Long.class,
                UUID.fromString(id))).isGreaterThanOrEqualTo(3);
    }

    @Test
    void tenantsCannotReadOrMutateOtherCompanies() throws Exception {
        Customer owner = customer();
        Customer other = customer();
        String id = create(owner);
        send(get("/api/payments/{id}", id), other.token(), null, null, 404);
        send(post("/api/payments/{id}/confirm", id), other.token(), "foreign-confirm", null, 404);
        send(get("/api/payments/{id}", id).header("X-Company-Id", owner.companyId()), other.token(), null, null, 403);
        send(get("/api/payments"), null, null, null, 401);
        send(put("/api/admin/companies/{id}/status", owner.companyId()), other.token(), null, Map.of("active", false), 403);
        assertThat(json(send(get("/api/payments"), other.token(), null, null, 200)).path("totalElements").asInt()).isZero();
    }

    @Test
    void auditorsReadTheirCompanyButCannotPostFinancialOperations() throws Exception {
        Customer merchant = customer();
        String admin = login("admin@nexora.test", "Test-admin-password-42");
        String email = UUID.randomUUID() + "@nexora.test";
        send(post("/api/admin/users"), admin, null, Map.of("email", email, "password", "Auditor-password-42",
                "role", "AUDITOR", "companyId", merchant.companyId()), 201);
        String auditor = login(email, "Auditor-password-42");
        String id = create(merchant);
        send(get("/api/payments/{id}", id), auditor, null, null, 200);
        send(post("/api/payments/{id}/confirm", id), auditor, "auditor-confirm", null, 403);
        send(post("/api/payments"), auditor, "auditor-create", paymentRequest("1.00"), 403);
        send(put("/api/companies/me"), auditor, null, Map.of("name", "Renamed", "registrationNumber", "123"), 403);
    }

    @Test
    void disablingCompanyTakesEffectForExistingTokens() throws Exception {
        Customer merchant = customer();
        String admin = login("admin@nexora.test", "Test-admin-password-42");
        send(get("/api/companies/me"), merchant.token(), null, null, 200);
        send(put("/api/admin/companies/{id}/status", merchant.companyId()), admin, null, Map.of("active", false), 200);
        send(get("/api/payments"), merchant.token(), null, null, 401);
        send(put("/api/admin/companies/{id}/status", merchant.companyId()), admin, null, Map.of("active", true), 200);
        send(get("/api/payments"), merchant.token(), null, null, 200);
    }

    @Test
    void validationAndCorrelationErrorsAreConsistent() throws Exception {
        Customer merchant = customer();
        send(post("/api/payments"), merchant.token(), "negative", paymentRequest("-1.00"), 400);
        send(post("/api/payments"), merchant.token(), "fraction", paymentRequest("1.001"), 400);
        send(post("/api/payments"), merchant.token(), null, paymentRequest("1.00"), 400);
        send(get("/api/payments?size=101"), merchant.token(), null, null, 400);
        MvcResult error = send(post("/api/payments").header("X-Correlation-Id", "integration-validation"),
                merchant.token(), "currency", Map.of("amount", "1.00", "currency", "ZZZ"), 400);
        assertThat(error.getResponse().getHeader("X-Correlation-Id")).isEqualTo("integration-validation");
        assertThat(json(error).path("correlationId").asText()).isEqualTo("integration-validation");
    }

    @Test
    void concurrentIdenticalHttpRequestsCreateOnePayment() throws Exception {
        Customer merchant = customer();
        var responses = parallel(6, () -> send(post("/api/payments"), merchant.token(), "same-key",
                paymentRequest("100.00"), 201));
        List<String> ids = new ArrayList<>();
        for (MvcResult response : responses) ids.add(json(response).path("id").asText());
        assertThat(ids.stream().distinct()).hasSize(1);
        assertThat(database.queryForObject("select count(*) from payments where company_id=?", Long.class,
                merchant.companyId())).isEqualTo(1);
    }

    @Test
    void concurrentRefundRequestsCannotOverdrawPayment() throws Exception {
        Customer merchant = customer();
        String id = create(merchant);
        send(post("/api/payments/{id}/confirm", id), merchant.token(), "confirm", null, 200);
        var responses = parallel(2, () -> raw(post("/api/payments/{id}/refunds", id), merchant.token(),
                UUID.randomUUID().toString(), Map.of("amount", "70.00")));
        assertThat(responses.stream().map(result -> result.getResponse().getStatus())).containsExactlyInAnyOrder(201, 409);
        assertThat(database.queryForObject("select sum(amount) from refunds where payment_id=?", java.math.BigDecimal.class,
                UUID.fromString(id))).isEqualByComparingTo("70.00");
    }

    @Test
    void cacheUsesTtlAndInvalidatesAfterCommittedProfileUpdate() throws Exception {
        Customer merchant = customer();
        String key = "nexora:company:v1:" + merchant.companyId();
        send(get("/api/companies/me"), merchant.token(), null, null, 200);
        assertThat(cache.getExpire(key)).isBetween(1L, 300L);
        send(put("/api/companies/me"), merchant.token(), null, Map.of("name", "Updated company", "registrationNumber", "1234"), 200);
        assertThat(cache.hasKey(key)).isFalse();
        assertThat(json(send(get("/api/companies/me"), merchant.token(), null, null, 200)).path("name").asText())
                .isEqualTo("Updated company");
        cache.opsForValue().set(key, "broken-json");
        assertThat(json(send(get("/api/companies/me"), merchant.token(), null, null, 200)).path("name").asText())
                .isEqualTo("Updated company");
    }

    @Test
    void logoutAndExpirationRejectTokensAndDatabaseNeverStoresRawTokens() throws Exception {
        Customer merchant = customer();
        assertThat(database.queryForObject("select count(*) from access_tokens where token_hash=?", Long.class,
                merchant.token())).isZero();
        send(post("/api/auth/logout"), merchant.token(), null, null, 204);
        send(get("/api/auth/me"), merchant.token(), null, null, 401);
        String renewed = login(merchant.email(), "Merchant-password-42");
        database.update("update access_tokens set expires_at=now()-interval '1 second' where user_id=?", merchant.userId());
        send(get("/api/auth/me"), renewed, null, null, 401);
    }

    @Test
    void concurrentCacheMissCannotRefillAnOldProfileAfterInvalidation() throws Exception {
        Customer merchant = customer();
        CountDownLatch profileRead = new CountDownLatch(1);
        CountDownLatch allowCacheWrite = new CountDownLatch(1);
        doAnswer(invocation -> {
            CompanyView company = invocation.getArgument(0);
            if (company.id().equals(merchant.companyId()) && company.name().equals("Integration Merchant")) {
                profileRead.countDown();
                if (!allowCacheWrite.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Writer did not start");
            }
            return invocation.callRealMethod();
        }).when(companyCache).put(any());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var reader = executor.submit(() -> send(get("/api/companies/me"), merchant.token(), null, null, 200));
            try {
                assertThat(profileRead.await(5, TimeUnit.SECONDS)).isTrue();
                var writer = executor.submit(() -> send(put("/api/companies/me"), merchant.token(), null,
                        Map.of("name", "Concurrent update", "registrationNumber", "updated"), 200));
                await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() ->
                        assertThat(database.queryForObject("select count(*) from pg_stat_activity where wait_event_type='Lock' and query like 'update companies%'",
                                Long.class)).isGreaterThan(0));
                allowCacheWrite.countDown();
                reader.get(5, TimeUnit.SECONDS);
                writer.get(5, TimeUnit.SECONDS);
                assertThat(json(send(get("/api/companies/me"), merchant.token(), null, null, 200)).path("name").asText())
                        .isEqualTo("Concurrent update");
            } finally {
                allowCacheWrite.countDown();
            }
        }
    }

    @Test
    void cancellationAndStatusPaginationRespectLifecycle() throws Exception {
        Customer merchant = customer();
        String id = create(merchant);
        send(post("/api/payments/{id}/cancel", id), merchant.token(), "cancel", null, 200);
        send(post("/api/payments/{id}/confirm", id), merchant.token(), "confirm-cancelled", null, 409);
        JsonNode cancelled = json(send(get("/api/payments?status=CANCELLED&size=1"), merchant.token(), null, null, 200));
        assertThat(cancelled.path("content")).hasSize(1);
        assertThat(cancelled.path("content").get(0).path("id").asText()).isEqualTo(id);
    }

    @Test
    @Order(Integer.MAX_VALUE)
    void redisOutageFallsBackToPostgres() throws Exception {
        Customer merchant = customer();
        redis.stop();
        assertThat(json(send(get("/api/companies/me"), merchant.token(), null, null, 200)).path("id").asText())
                .isEqualTo(merchant.companyId().toString());
    }

    private Customer customer() throws Exception {
        String email = UUID.randomUUID() + "@nexora.test";
        JsonNode registered = json(send(post("/api/auth/register"), null, null, Map.of("email", email,
                "password", "Merchant-password-42", "companyName", "Integration Merchant", "registrationNumber", UUID.randomUUID().toString()), 201));
        return new Customer(login(email, "Merchant-password-42"), UUID.fromString(registered.path("companyId").asText()),
                UUID.fromString(registered.path("id").asText()), email);
    }

    private String login(String email, String password) throws Exception {
        return json(send(post("/api/auth/login"), null, null, Map.of("email", email, "password", password), 200))
                .path("accessToken").asText();
    }

    private String create(Customer customer) throws Exception {
        return json(send(post("/api/payments"), customer.token(), UUID.randomUUID().toString(), paymentRequest("100.00"), 201))
                .path("id").asText();
    }

    private Map<String, Object> paymentRequest(String amount) {
        return Map.of("amount", amount, "currency", "KZT", "description", "Virtual purchase", "scenario", "SUCCESS");
    }

    private MvcResult send(MockHttpServletRequestBuilder request, String token, String key, Object body, int status) throws Exception {
        MvcResult response = raw(request, token, key, body);
        assertThat(response.getResponse().getStatus()).as(response.getResponse().getContentAsString()).isEqualTo(status);
        return response;
    }

    private MvcResult raw(MockHttpServletRequestBuilder request, String token, String key, Object body) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (key != null) request.header("Idempotency-Key", key);
        if (body != null) request.contentType("application/json").content(mapper.writeValueAsString(body));
        return http.perform(request).andReturn();
    }

    private JsonNode json(MvcResult response) throws Exception {
        return mapper.readTree(response.getResponse().getContentAsString());
    }

    private <T> List<T> parallel(int count, Callable<T> operation) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(count)) {
            var futures = new ArrayList<java.util.concurrent.Future<T>>();
            for (int i = 0; i < count; i++) futures.add(executor.submit(() -> { start.await(); return operation.call(); }));
            start.countDown();
            var results = new ArrayList<T>();
            for (var future : futures) results.add(future.get(30, TimeUnit.SECONDS));
            return results;
        }
    }

    private record Customer(String token, UUID companyId, UUID userId, String email) {}
}
