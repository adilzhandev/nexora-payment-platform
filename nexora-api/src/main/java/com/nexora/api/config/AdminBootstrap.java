package com.nexora.api.config;

import com.nexora.api.auth.AuthService;
import com.nexora.api.user.Role;
import com.nexora.api.user.UserRepository;
import com.nexora.api.user.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class AdminBootstrap implements ApplicationRunner {
    private final UserRepository users;
    private final UserService service;
    private final String email;
    private final String password;

    public AdminBootstrap(UserRepository users, UserService service,
                          @Value("${nexora.bootstrap.email:}") String email,
                          @Value("${nexora.bootstrap.password:}") String password) {
        this.users = users;
        this.service = service;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email.isBlank() && password.isBlank()) return;
        if (email.isBlank() || password.isBlank()) {
            throw new IllegalStateException("Both bootstrap email and password must be configured");
        }
        var existing = users.findByEmail(AuthService.normalizeEmail(email));
        if (existing.isPresent() && existing.get().getRole() != Role.ADMIN) {
            throw new IllegalStateException("Bootstrap email belongs to a non-administrator account");
        }
        if (existing.isPresent() && !existing.get().isActive()) {
            throw new IllegalStateException("Bootstrap administrator account is inactive");
        }
        if (existing.isEmpty()) {
            service.create(email, password, Role.ADMIN, null);
        }
    }
}
