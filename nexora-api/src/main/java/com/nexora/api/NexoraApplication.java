package com.nexora.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.nexora", exclude = UserDetailsServiceAutoConfiguration.class)
@EntityScan("com.nexora")
@EnableJpaRepositories("com.nexora")
@EnableScheduling
public class NexoraApplication {
    public static void main(String[] args) {
        SpringApplication.run(NexoraApplication.class, args);
    }
}
