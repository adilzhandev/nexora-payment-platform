package com.nexora.audit.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class AuditSecurityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    UserDetailsService auditUsers(@Value("${nexora.audit.username}") String username,
                                 @Value("${nexora.audit.password}") String password,
                                 PasswordEncoder encoder) {
        if (username.isBlank() || password.length() < 16) {
            throw new IllegalArgumentException("Audit username and a password of at least 16 characters are required");
        }
        return new InMemoryUserDetailsManager(User.withUsername(username)
                .password(encoder.encode(password)).roles("AUDITOR").build());
    }

    @Bean
    SecurityFilterChain auditSecurity(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().hasRole("AUDITOR"))
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    @Bean
    OpenAPI auditOpenApi() {
        return new OpenAPI().info(new Info().title("Nexora Audit API").version("1.0"))
                .components(new Components().addSecuritySchemes("auditBasic",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")));
    }
}
