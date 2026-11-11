package com.nexora.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.common.event.EventWriter;
import com.nexora.common.event.FinancialEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class JdbcEventWriter implements EventWriter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcEventWriter(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(FinancialEvent event) {
        try {
            jdbc.update("""
                    INSERT INTO outbox_events (event_id, entity_id, company_id, event_type, payload)
                    VALUES (?, ?, ?, ?, CAST(? AS jsonb))
                    """, event.eventId(), event.entityId(), event.companyId(), event.eventType(),
                    mapper.writeValueAsString(event));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Event cannot be serialized", exception);
        }
    }
}
