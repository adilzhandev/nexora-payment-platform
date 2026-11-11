package com.nexora.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class EventConfiguration {
    @Bean
    NewTopic paymentEventsTopic(@Value("${nexora.events.topic:nexora.payments.v1}") String topic,
                               @Value("${nexora.events.partitions:3}") int partitions,
                               @Value("${nexora.events.replicas:1}") int replicas) {
        return TopicBuilder.name(topic).partitions(partitions).replicas(replicas).build();
    }
}
