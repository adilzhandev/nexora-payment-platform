package com.nexora.api.company;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class CompanyCache {
    private static final Logger log = LoggerFactory.getLogger(CompanyCache.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Duration ttl;

    public CompanyCache(StringRedisTemplate redis, ObjectMapper mapper,
                        @Value("${nexora.company-cache.ttl:PT5M}") Duration ttl) {
        this.redis = redis;
        this.mapper = mapper;
        this.ttl = ttl;
    }

    public Optional<CompanyView> get(UUID id) {
        try {
            String json = redis.opsForValue().get(key(id));
            if (json == null) return Optional.empty();
            CompanyView company = mapper.readValue(json, CompanyView.class);
            if (company == null || !id.equals(company.id())) {
                evict(id);
                return Optional.empty();
            }
            return Optional.of(company);
        } catch (DataAccessException | JsonProcessingException exception) {
            log.warn("Company cache read failed; using PostgreSQL");
            return Optional.empty();
        }
    }

    public void put(CompanyView company) {
        try {
            redis.opsForValue().set(key(company.id()), mapper.writeValueAsString(company), ttl);
        } catch (DataAccessException | JsonProcessingException exception) {
            log.warn("Company cache write failed");
        }
    }

    public void evict(UUID id) {
        try {
            redis.delete(key(id));
        } catch (DataAccessException exception) {
            log.warn("Company cache invalidation failed; entry expires by TTL");
        }
    }

    private String key(UUID id) { return "nexora:company:v1:" + id; }
}
