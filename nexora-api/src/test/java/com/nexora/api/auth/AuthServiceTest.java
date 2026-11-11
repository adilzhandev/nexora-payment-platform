package com.nexora.api.auth;

import com.nexora.api.company.Company;
import com.nexora.api.company.CompanyRepository;
import com.nexora.api.error.ApiException;
import com.nexora.api.user.AppUser;
import com.nexora.api.user.Role;
import com.nexora.api.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {
    private final CompanyRepository companies = mock(CompanyRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final TokenRepository tokens = mock(TokenRepository.class);
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(4);
    private final AuthService service = new AuthService(companies, users, tokens, passwords, Duration.ofHours(12));
    private final String token = "a".repeat(43);

    @Test
    void tokenTtlMustBePositive() {
        assertThatThrownBy(() -> new AuthService(companies, users, tokens, passwords, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Token TTL must be positive");
        assertThatThrownBy(() -> new AuthService(companies, users, tokens, passwords, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Token TTL must be positive");
    }

    @Test
    void authenticationRejectsExpiredTokens() {
        AppUser user = merchant(new Company("Merchant", "123"));
        when(tokens.findById(AuthService.hashToken(token)))
                .thenReturn(Optional.of(new AccessToken(AuthService.hashToken(token), user, Instant.now().minusSeconds(1))));
        assertThat(service.authenticate(token)).isEmpty();
    }

    @Test
    void authenticationRechecksCompanyStatus() {
        Company company = new Company("Merchant", "123");
        AppUser user = merchant(company);
        when(tokens.findById(AuthService.hashToken(token)))
                .thenReturn(Optional.of(new AccessToken(AuthService.hashToken(token), user, Instant.now().plusSeconds(60))));
        assertThat(service.authenticate(token)).isPresent();
        company.setActive(false);
        assertThat(service.authenticate(token)).isEmpty();
    }

    @Test
    void loginReturnsRandomTokenAndPersistsOnlyItsDigest() {
        AppUser user = merchant(new Company("Merchant", "123"));
        when(users.findByEmail("merchant@example.test")).thenReturn(Optional.of(user));
        var result = service.login("MERCHANT@example.test", "strong-test-password");
        assertThat(result.accessToken()).hasSize(43);
        assertThat(result.user().companyId()).isEqualTo(user.getCompany().getId());
        var stored = ArgumentCaptor.forClass(AccessToken.class);
        verify(tokens).save(stored.capture());
        assertThat(ReflectionTestUtils.getField(stored.getValue(), "tokenHash"))
                .isEqualTo(AuthService.hashToken(result.accessToken())).isNotEqualTo(result.accessToken());
    }

    @Test
    void wrongPasswordHasSamePublicErrorAsMissingUser() {
        when(users.findByEmail("merchant@example.test")).thenReturn(Optional.of(merchant(new Company("Merchant", "123"))));
        assertThatThrownBy(() -> service.login("merchant@example.test", "wrong-password"))
                .isInstanceOf(ApiException.class).hasMessage("Invalid credentials or inactive account");
        assertThatThrownBy(() -> service.login("missing@example.test", "wrong-password"))
                .isInstanceOf(ApiException.class).hasMessage("Invalid credentials or inactive account");
    }

    @Test
    void rejectsPasswordsThatExceedBcryptByteLimit() {
        assertThatThrownBy(() -> PasswordPolicy.validate("я".repeat(40))).isInstanceOf(ApiException.class);
    }

    private AppUser merchant(Company company) {
        return new AppUser("merchant@example.test", passwords.encode("strong-test-password"), Role.MERCHANT, company);
    }
}
