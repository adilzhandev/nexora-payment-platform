package com.nexora.audit.consumer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexora.audit.history.AuditHistoryRepository;
import com.nexora.common.event.FinancialEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class AuditEventConsumerTest {
    private final EventValidator validator = mock(EventValidator.class);
    private final AuditHistoryRepository repository = mock(AuditHistoryRepository.class);
    private final AuditEventConsumer consumer = new AuditEventConsumer(validator, repository);

    @Test
    void propagatesDatabaseFailureSoKafkaDoesNotAcknowledge() {
        FinancialEvent event = new FinancialEvent(UUID.randomUUID(), "PaymentCreated", UUID.randomUUID(),
                UUID.randomUUID(), Instant.now(), 1, "request-123", Map.of());
        when(validator.read("payload")).thenReturn(event);
        when(repository.insert(event, "payload")).thenThrow(new DataAccessResourceFailureException("Database unavailable"));

        assertThatThrownBy(() -> consumer.accept("payload")).isInstanceOf(DataAccessResourceFailureException.class);
        verify(repository).insert(event, "payload");
    }

    @Test
    void rejectsInvalidEventBeforePersistence() {
        when(validator.read("invalid")).thenThrow(new InvalidEventException("Invalid event"));
        assertThatThrownBy(() -> consumer.accept("invalid")).isInstanceOf(InvalidEventException.class);
        verifyNoInteractions(repository);
    }
}
