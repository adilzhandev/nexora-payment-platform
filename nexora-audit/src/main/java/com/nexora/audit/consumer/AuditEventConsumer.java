package com.nexora.audit.consumer;

import com.nexora.audit.history.AuditHistoryRepository;
import com.nexora.common.event.FinancialEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AuditEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(AuditEventConsumer.class);
    private final EventValidator validator;
    private final AuditHistoryRepository history;

    public AuditEventConsumer(EventValidator validator, AuditHistoryRepository history) {
        this.validator = validator;
        this.history = history;
    }

    @Transactional
    @KafkaListener(topics = "${nexora.events.topic:nexora.payments.v1}",
            autoStartup = "${nexora.audit.consumer-enabled:true}")
    public void accept(String payload) {
        FinancialEvent event = validator.read(payload);
        try (MDC.MDCCloseable ignored = MDC.putCloseable("correlationId", event.correlationId())) {
            boolean inserted = history.insert(event, payload);
            log.info("Audit event processed: eventId={}, eventType={}, inserted={}",
                    event.eventId(), event.eventType(), inserted);
        }
    }
}
