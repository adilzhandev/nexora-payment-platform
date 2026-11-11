package com.nexora.core.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.core.error.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotencyService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public IdempotencyService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public <T> T execute(UUID companyId, String key, String operation, Object request,
                         Class<T> responseType, Supplier<T> action) {
        if (key == null || key.isBlank() || key.length() > 128 || key.chars().anyMatch(Character::isISOControl)) {
            throw DomainException.invalid("Idempotency-Key must contain 1 to 128 printable characters");
        }
        String fingerprint = hash(operation + ":" + encode(request));
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class,
                companyId + ":idempotency:" + key);
        var previous = jdbc.query("select fingerprint, response from idempotency_records where company_id = ? and idempotency_key = ?",
                (rs, row) -> new SavedResponse(rs.getString("fingerprint"), rs.getString("response")), companyId, key);
        if (!previous.isEmpty()) {
            SavedResponse saved = previous.getFirst();
            if (!saved.fingerprint().equals(fingerprint)) {
                throw DomainException.conflict("Idempotency-Key was already used with a different request");
            }
            try {
                return mapper.readValue(saved.response(), responseType);
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Cannot read stored operation result", exception);
            }
        }
        T result = action.get();
        jdbc.update("insert into idempotency_records (company_id, idempotency_key, operation, fingerprint, response) values (?, ?, ?, ?, ?::jsonb)",
                companyId, key, operation, fingerprint, encode(result));
        return result;
    }

    private String encode(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize operation", exception);
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record SavedResponse(String fingerprint, String response) {}
}
