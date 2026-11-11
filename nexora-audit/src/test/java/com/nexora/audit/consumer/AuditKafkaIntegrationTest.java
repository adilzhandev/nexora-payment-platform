package com.nexora.audit.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.common.event.FinancialEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

@Tag("integration")
@Testcontainers
@SpringBootTest(properties = {"nexora.audit.username=test-auditor",
        "nexora.audit.password=test-audit-password-1234"})
@AutoConfigureMockMvc
class AuditKafkaIntegrationTest {
    private static final String TOPIC = "nexora.payments.v1";
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.9.1");

    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Test
    void consumesDuplicateDeliveryExactlyOnceAndProtectsHistory() throws Exception {
        FinancialEvent event = event();
        String payload = mapper.writeValueAsString(event);
        kafka.send(TOPIC, event.entityId().toString(), payload).get(10, TimeUnit.SECONDS);
        RecordMetadata duplicate = kafka.send(TOPIC, event.entityId().toString(), payload)
                .get(10, TimeUnit.SECONDS).getRecordMetadata();
        await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
            assertThat(count(event.eventId())).isEqualTo(1);
            assertThat(committedOffset(duplicate.partition())).isGreaterThan(duplicate.offset());
        });
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_events WHERE event_id = ?", event.eventId()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_events SET event_type = 'PaymentFailed' WHERE event_id = ?", event.eventId()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void sendsInvalidEnvelopeToDeadLetterTopicAndContinues() throws Exception {
        String invalid = "invalid-event-" + UUID.randomUUID();
        FinancialEvent valid = event();
        kafka.send(TOPIC, 0, "invalid", invalid).get(10, TimeUnit.SECONDS);
        kafka.send(TOPIC, 0, valid.entityId().toString(), mapper.writeValueAsString(valid))
                .get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> assertThat(count(valid.eventId())).isEqualTo(1));
        try (KafkaConsumer<String, String> consumer = deadLetterConsumer()) {
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertThat(consumer.poll(Duration.ofMillis(500)))
                            .anySatisfy(record -> assertThat(record.value()).isEqualTo(invalid)));
        }
    }

    @Test
    void retriesDatabaseFailureAndPersistsAfterRecovery() throws Exception {
        FinancialEvent event = event();
        jdbc.execute("ALTER TABLE audit_events RENAME TO temporarily_unavailable_events");
        try {
            RecordMetadata sent = kafka.send(TOPIC, 1, event.entityId().toString(), mapper.writeValueAsString(event))
                    .get(10, TimeUnit.SECONDS).getRecordMetadata();
            await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(committedOffset(sent.partition())).isLessThanOrEqualTo(sent.offset());
                assertThat(jdbc.queryForObject("SELECT count(*) FROM temporarily_unavailable_events WHERE event_id = ?",
                        Long.class, event.eventId())).isZero();
            });
        } finally {
            jdbc.execute("ALTER TABLE temporarily_unavailable_events RENAME TO audit_events");
        }
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(count(event.eventId())).isEqualTo(1));
    }

    @Test
    void requiresDedicatedAuditCredentialsAndScopesQueriesByCompany() throws Exception {
        FinancialEvent event = event();
        kafka.send(TOPIC, event.entityId().toString(), mapper.writeValueAsString(event)).get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(count(event.eventId())).isEqualTo(1));
        mvc.perform(get("/api/v1/audit/events").param("companyId", event.companyId().toString()))
                .andExpect(status().isUnauthorized());
        String response = mvc.perform(get("/api/v1/audit/events")
                        .param("companyId", event.companyId().toString())
                        .with(httpBasic("test-auditor", "test-audit-password-1234")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(response).contains(event.eventId().toString());
        String otherCompany = mvc.perform(get("/api/v1/audit/events")
                        .param("companyId", UUID.randomUUID().toString())
                        .with(httpBasic("test-auditor", "test-audit-password-1234")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(otherCompany).isEqualTo("[]");
        mvc.perform(get("/api/v1/audit/events").param("companyId", event.companyId().toString()).param("size", "101")
                        .with(httpBasic("test-auditor", "test-audit-password-1234")))
                .andExpect(status().isBadRequest());
    }

    private long committedOffset(int partition) throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            var offsets = admin.listConsumerGroupOffsets("nexora-audit-v1").partitionsToOffsetAndMetadata()
                    .get(5, TimeUnit.SECONDS);
            var offset = offsets.get(new TopicPartition(TOPIC, partition));
            return offset == null ? 0 : offset.offset();
        }
    }

    private KafkaConsumer<String, String> deadLetterConsumer() {
        var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(List.of(TOPIC + ".DLT"));
        return consumer;
    }

    private long count(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE event_id = ?", Long.class, id);
    }

    private FinancialEvent event() {
        return new FinancialEvent(UUID.randomUUID(), "PaymentSucceeded", UUID.randomUUID(), UUID.randomUUID(),
                Instant.now(), 1, UUID.randomUUID().toString(), Map.of("amount", "100.00", "currency", "KZT"));
    }
}
