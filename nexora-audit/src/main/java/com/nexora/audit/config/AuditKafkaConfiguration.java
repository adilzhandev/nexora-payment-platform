package com.nexora.audit.config;

import com.nexora.audit.consumer.InvalidEventException;
import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class AuditKafkaConfiguration {
    @Bean
    DefaultErrorHandler auditErrorHandler(KafkaTemplate<String, String> kafka) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafka,
                (record, exception) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(15));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer,
                new FixedBackOff(1000, FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.addNotRetryableExceptions(InvalidEventException.class);
        return handler;
    }

    @Bean
    NewTopic auditSourceTopic(@Value("${nexora.events.topic:nexora.payments.v1}") String topic,
                             @Value("${nexora.events.partitions:3}") int partitions,
                             @Value("${nexora.events.replicas:1}") int replicas) {
        return TopicBuilder.name(topic).partitions(partitions).replicas(replicas).build();
    }

    @Bean
    NewTopic auditDeadLetterTopic(@Value("${nexora.events.topic:nexora.payments.v1}") String topic,
                                 @Value("${nexora.events.partitions:3}") int partitions,
                                 @Value("${nexora.events.replicas:1}") int replicas) {
        return TopicBuilder.name(topic + ".DLT").partitions(partitions).replicas(replicas).build();
    }
}
