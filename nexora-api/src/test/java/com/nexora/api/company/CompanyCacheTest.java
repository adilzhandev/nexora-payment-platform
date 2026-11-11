package com.nexora.api.company;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompanyCacheTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final CompanyCache cache = new CompanyCache(redis, mapper, Duration.ofMinutes(5));

    @BeforeEach
    void setUp() { when(redis.opsForValue()).thenReturn(values); }

    @Test
    void roundTripsProfileWithBoundedTtl() throws Exception {
        var company = CompanyView.from(new Company("Merchant", "123"));
        String json = mapper.writeValueAsString(company);
        cache.put(company);
        verify(values).set("nexora:company:v1:" + company.id(), json, Duration.ofMinutes(5));
        when(values.get("nexora:company:v1:" + company.id())).thenReturn(json);
        assertThat(cache.get(company.id())).contains(company);
    }

    @Test
    void unavailableRedisDoesNotBreakReadsOrInvalidation() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("offline"));
        when(redis.delete(anyString())).thenThrow(new RedisConnectionFailureException("offline"));
        assertThat(cache.get(UUID.randomUUID())).isEmpty();
        assertThatCode(() -> cache.evict(UUID.randomUUID())).doesNotThrowAnyException();
    }

    @Test
    void malformedJsonFallsBackToDatabase() {
        when(values.get(anyString())).thenReturn("invalid-json");
        assertThat(cache.get(UUID.randomUUID())).isEmpty();
    }
}
