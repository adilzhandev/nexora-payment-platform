package com.nexora.audit.history;

import com.nexora.audit.consumer.EventValidator;
import com.nexora.audit.consumer.InvalidEventException;
import com.nexora.common.event.FinancialEvent;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuditHistoryRepository {
    private final JdbcTemplate jdbc;
    private final EventValidator validator;

    public AuditHistoryRepository(JdbcTemplate jdbc, EventValidator validator) {
        this.jdbc = jdbc;
        this.validator = validator;
    }

    public boolean insert(FinancialEvent event, String payload) {
        int inserted = jdbc.update("""
                INSERT INTO audit_events
                  (event_id, entity_id, company_id, event_type, occurred_at, schema_version, correlation_id, payload)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                ON CONFLICT (event_id) DO NOTHING
                """, event.eventId(), event.entityId(), event.companyId(), event.eventType(),
                Timestamp.from(event.timestamp()), event.schemaVersion(), event.correlationId(), payload);
        if (inserted == 0) {
            Boolean samePayload = jdbc.queryForObject(
                    "SELECT payload = CAST(? AS jsonb) FROM audit_events WHERE event_id = ?",
                    Boolean.class, payload, event.eventId());
            if (!Boolean.TRUE.equals(samePayload)) {
                throw new InvalidEventException("Event ID was already used with a different payload");
            }
        }
        return inserted == 1;
    }

    public List<FinancialEvent> find(UUID companyId, UUID entityId, int page, int size) {
        long offset = (long) page * size;
        if (entityId == null) {
            return jdbc.query("""
                    SELECT payload::text FROM audit_events WHERE company_id = ?
                    ORDER BY occurred_at DESC, event_id DESC LIMIT ? OFFSET ?
                    """, (result, row) -> validator.read(result.getString(1)), companyId, size, offset);
        }
        return jdbc.query("""
                SELECT payload::text FROM audit_events WHERE company_id = ? AND entity_id = ?
                ORDER BY occurred_at DESC, event_id DESC LIMIT ? OFFSET ?
                """, (result, row) -> validator.read(result.getString(1)), companyId, entityId, size, offset);
    }
}
