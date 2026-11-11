package com.nexora.events;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name = "nexora.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transaction;
    private final String topic;
    private final int batchSize;
    private final long sendTimeoutMs;

    public OutboxPublisher(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka,
                           PlatformTransactionManager transactionManager,
                           @Value("${nexora.events.topic:nexora.payments.v1}") String topic,
                           @Value("${nexora.outbox.batch-size:25}") int batchSize,
                           @Value("${nexora.outbox.send-timeout-ms:5000}") long sendTimeoutMs) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.transaction = new TransactionTemplate(transactionManager);
        this.topic = topic;
        this.batchSize = Math.max(1, Math.min(batchSize, 100));
        this.sendTimeoutMs = Math.max(100, sendTimeoutMs);
    }

    @Scheduled(fixedDelayString = "${nexora.outbox.fixed-delay-ms:1000}")
    public void publishPendingEvents() {
        for (int index = 0; index < batchSize; index++) {
            if (Thread.currentThread().isInterrupted()
                    || !Boolean.TRUE.equals(transaction.execute(status -> publishNext()))) {
                return;
            }
        }
    }

    private boolean publishNext() {
        List<PendingEvent> rows = jdbc.query("""
                SELECT event_id, entity_id, payload::text, attempt_count
                FROM outbox_events current_event
                WHERE published_at IS NULL AND next_attempt_at <= now()
                  AND NOT EXISTS (
                    SELECT 1 FROM outbox_events earlier
                    WHERE earlier.entity_id = current_event.entity_id
                      AND earlier.sequence < current_event.sequence
                      AND earlier.published_at IS NULL
                  )
                ORDER BY sequence
                LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (result, row) -> new PendingEvent(result.getObject("event_id", UUID.class),
                result.getObject("entity_id", UUID.class), result.getString("payload"),
                result.getInt("attempt_count")));
        if (rows.isEmpty()) {
            return false;
        }
        PendingEvent event = rows.getFirst();
        try {
            kafka.send(topic, event.entityId().toString(), event.payload())
                    .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            jdbc.update("UPDATE outbox_events SET published_at = now(), last_error = NULL WHERE event_id = ?",
                    event.eventId());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            retryLater(event, exception);
        } catch (Exception exception) {
            retryLater(event, exception);
        }
        return true;
    }

    private void retryLater(PendingEvent event, Exception exception) {
        long retrySeconds = Math.min(300, 1L << Math.min(event.attempts(), 8));
        String failure = exception.getClass().getSimpleName();
        jdbc.update("""
                UPDATE outbox_events
                SET attempt_count = attempt_count + 1,
                    next_attempt_at = now() + (? * interval '1 second'), last_error = ?
                WHERE event_id = ?
                """, retrySeconds, failure, event.eventId());
        log.warn("Outbox publication deferred: eventId={}, retryIn={}, reason={}",
                event.eventId(), Duration.ofSeconds(retrySeconds), failure);
    }

    private record PendingEvent(UUID eventId, UUID entityId, String payload, int attempts) {
    }
}
