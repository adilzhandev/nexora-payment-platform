package com.nexora.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.common.event.EventWriter;
import com.nexora.common.event.FinancialEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@Tag("integration")
@Testcontainers
@SpringBootTest(classes = OutboxIntegrationTest.TestApplication.class)
class OutboxIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.9.1");

    @Autowired EventWriter writer;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ObjectMapper mapper;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.producer.key-serializer", () -> StringSerializer.class.getName());
        registry.add("spring.kafka.producer.value-serializer", () -> StringSerializer.class.getName());
        registry.add("spring.kafka.producer.properties.max.block.ms", () -> "3000");
    }

    @BeforeAll
    static void migrate(@Autowired JdbcTemplate jdbc) {
        new ResourceDatabasePopulator(new ClassPathResource("db/outbox.sql"))
                .execute(jdbc.getDataSource());
    }

    @Test
    void requiresFinancialTransactionAndRollsBackWithIt() {
        FinancialEvent event = event(UUID.randomUUID(), "PaymentCreated");
        assertThatThrownBy(() -> writer.append(event)).isInstanceOf(IllegalTransactionStateException.class);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.execute(status -> {
            writer.append(event);
            throw new IllegalStateException("Financial transaction failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count(event.eventId())).isZero();
    }

    @Test
    void publishesCommittedEventToRealBroker() throws Exception {
        FinancialEvent event = event(UUID.randomUUID(), "PaymentSucceeded");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> writer.append(event));
        String topic = "outbox-" + UUID.randomUUID();
        publisher(kafka, topic).publishPendingEvents();

        assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM outbox_events WHERE event_id = ?",
                Boolean.class, event.eventId())).isTrue();
        try (KafkaConsumer<String, String> consumer = consumer(topic)) {
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                var records = consumer.poll(Duration.ofMillis(500));
                assertThat(records).anySatisfy(record -> {
                    assertThat(record.key()).isEqualTo(event.entityId().toString());
                    assertThat(record.value()).contains(event.eventId().toString());
                });
            });
        }
    }

    @Test
    void retriesBrokerFailureWithoutOvertakingEarlierEntityEvent() {
        UUID entityId = UUID.randomUUID();
        FinancialEvent first = event(entityId, "PaymentCreated");
        FinancialEvent second = event(entityId, "PaymentSucceeded");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            writer.append(first);
            writer.append(second);
        });
        Map<String, Object> properties = new HashMap<>();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:1");
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 100);
        DefaultKafkaProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(properties);
        try {
            KafkaTemplate<String, String> unavailableBroker = new KafkaTemplate<>(factory);
            publisher(unavailableBroker, "outbox-retry").publishPendingEvents();
        } finally {
            factory.destroy();
        }
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM outbox_events WHERE event_id = ?",
                Integer.class, first.eventId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM outbox_events WHERE event_id = ?",
                Integer.class, second.eventId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE entity_id = ? AND published_at IS NULL",
                Long.class, entityId)).isEqualTo(2);

        jdbc.update("UPDATE outbox_events SET next_attempt_at = now() WHERE event_id = ?", first.eventId());
        publisher(kafka, "outbox-recovered-" + UUID.randomUUID()).publishPendingEvents();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE entity_id = ? AND published_at IS NOT NULL",
                Long.class, entityId)).isEqualTo(2);
    }

    private OutboxPublisher publisher(KafkaTemplate<String, String> producer, String topic) {
        return new OutboxPublisher(jdbc, producer, transactionManager, topic, 25, 5000);
    }

    private KafkaConsumer<String, String> consumer(String topic) {
        var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(java.util.List.of(topic));
        return consumer;
    }

    private long count(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_id = ?", Long.class, eventId);
    }

    private FinancialEvent event(UUID entityId, String type) {
        return new FinancialEvent(UUID.randomUUID(), type, entityId, UUID.randomUUID(), Instant.now(),
                1, "integration-request", Map.of("currency", "KZT"));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(JdbcEventWriter.class)
    static class TestApplication {
    }
}
