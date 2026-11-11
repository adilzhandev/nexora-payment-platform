package com.nexora.api.auth;

import com.nexora.api.company.Company;
import com.nexora.api.config.AdminBootstrap;
import com.nexora.api.user.AppUser;
import com.nexora.api.user.Role;
import com.nexora.api.user.UserRepository;
import com.nexora.api.user.UserService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminBootstrapTest {
    private final UserRepository users = mock(UserRepository.class);
    private final UserService service = mock(UserService.class);
    private final DefaultApplicationArguments arguments = new DefaultApplicationArguments(new String[0]);

    @Test
    void rejectsEmailOwnedByAMerchant() {
        var merchant = new AppUser("admin@example.test", "hash", Role.MERCHANT, new Company("Company", "123"));
        when(users.findByEmail("admin@example.test")).thenReturn(Optional.of(merchant));
        var bootstrap = new AdminBootstrap(users, service, "admin@example.test", "bootstrap-password");
        assertThatThrownBy(() -> bootstrap.run(arguments)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap email belongs to a non-administrator account");
        verifyNoInteractions(service);
    }

    @Test
    void inactiveAdministratorIsNotSilentlyReactivated() {
        var administrator = new AppUser("admin@example.test", "existing-hash", Role.ADMIN, null);
        administrator.setActive(false);
        when(users.findByEmail("admin@example.test")).thenReturn(Optional.of(administrator));
        var bootstrap = new AdminBootstrap(users, service, "admin@example.test", "bootstrap-password");
        assertThatThrownBy(() -> bootstrap.run(arguments)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap administrator account is inactive");
        verifyNoInteractions(service);
    }

    @Test
    void existingAdministratorIsNotModified() {
        var administrator = new AppUser("admin@example.test", "existing-hash", Role.ADMIN, null);
        when(users.findByEmail("admin@example.test")).thenReturn(Optional.of(administrator));
        new AdminBootstrap(users, service, "admin@example.test", "bootstrap-password").run(arguments);
        verifyNoInteractions(service);
    }

    @Test
    void explicitCredentialsCreateAdministrator() {
        new AdminBootstrap(users, service, "admin@example.test", "bootstrap-password").run(arguments);
        verify(service).create("admin@example.test", "bootstrap-password", Role.ADMIN, null);
    }

    @Test
    void partialConfigurationFailsInsteadOfCreatingAnUnusableAccount() {
        var bootstrap = new AdminBootstrap(users, service, "admin@example.test", "");
        assertThatThrownBy(() -> bootstrap.run(arguments)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(service);
    }

    @Test
    void absentConfigurationDoesNotCreateDefaultCredentials() {
        new AdminBootstrap(users, service, "", "").run(arguments);
        verifyNoInteractions(users, service);
    }
}
