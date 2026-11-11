package com.nexora.api.auth;

import com.nexora.api.company.Company;
import com.nexora.api.company.CompanyRepository;
import com.nexora.api.error.ApiException;
import com.nexora.api.user.AppUser;
import com.nexora.api.user.Role;
import com.nexora.api.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final CompanyRepository companies;
    private final UserRepository users;
    private final TokenRepository tokens;
    private final PasswordEncoder passwords;
    private final Duration tokenTtl;
    private final SecureRandom random = new SecureRandom();
    private final String dummyPasswordHash;

    public AuthService(CompanyRepository companies, UserRepository users, TokenRepository tokens,
                       PasswordEncoder passwords, @Value("${nexora.auth.token-ttl:PT12H}") Duration tokenTtl) {
        this.companies = companies;
        this.users = users;
        this.tokens = tokens;
        this.passwords = passwords;
        if (tokenTtl == null || tokenTtl.isZero() || tokenTtl.isNegative()) {
            throw new IllegalArgumentException("Token TTL must be positive");
        }
        this.tokenTtl = tokenTtl;
        this.dummyPasswordHash = passwords.encode(Base64.getEncoder().encodeToString(random.generateSeed(32)));
    }

    @Transactional
    public CurrentUser register(String email, String password, String companyName, String registrationNumber) {
        PasswordPolicy.validate(password);
        String normalizedEmail = normalizeEmail(email);
        if (users.existsByEmail(normalizedEmail)) {
            throw new ApiException(HttpStatus.CONFLICT, "Email is already registered");
        }
        Company company = companies.save(new Company(companyName.strip(), registrationNumber.strip()));
        AppUser user = users.save(new AppUser(normalizedEmail, passwords.encode(password), Role.MERCHANT, company));
        return CurrentUser.from(user);
    }

    @Transactional
    public LoginResponse login(String email, String password) {
        AppUser user = users.findByEmail(normalizeEmail(email)).orElse(null);
        String hash = user == null ? dummyPasswordHash : user.getPasswordHash();
        boolean passwordMatches = password.getBytes(StandardCharsets.UTF_8).length <= 72
                && passwords.matches(password, hash);
        if (!passwordMatches || user == null || !isEnabled(user)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid credentials or inactive account");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = Instant.now().plus(tokenTtl);
        tokens.save(new AccessToken(hashToken(token), user, expiresAt));
        return new LoginResponse(token, "Bearer", expiresAt, CurrentUser.from(user));
    }

    @Transactional(readOnly = true)
    public Optional<CurrentUser> authenticate(String token) {
        if (token == null || token.length() != 43) {
            return Optional.empty();
        }
        return tokens.findById(hashToken(token))
                .filter(access -> access.getExpiresAt().isAfter(Instant.now()))
                .map(AccessToken::getUser)
                .filter(this::isEnabled)
                .map(CurrentUser::from);
    }

    @Transactional
    public void logout(String token) {
        tokens.deleteById(hashToken(token));
    }

    private boolean isEnabled(AppUser user) {
        return user.isActive() && (user.getCompany() == null || user.getCompany().isActive());
    }

    public static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    public static String hashToken(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record LoginResponse(String accessToken, String tokenType, Instant expiresAt, CurrentUser user) {}
}
