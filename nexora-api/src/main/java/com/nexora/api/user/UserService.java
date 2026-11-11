package com.nexora.api.user;

import com.nexora.api.auth.AuthService;
import com.nexora.api.auth.CurrentUser;
import com.nexora.api.auth.PasswordPolicy;
import com.nexora.api.company.Company;
import com.nexora.api.company.CompanyRepository;
import com.nexora.api.error.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
    private final UserRepository users;
    private final CompanyRepository companies;
    private final PasswordEncoder passwords;

    public UserService(UserRepository users, CompanyRepository companies, PasswordEncoder passwords) {
        this.users = users;
        this.companies = companies;
        this.passwords = passwords;
    }

    @Transactional
    public CurrentUser create(String email, String password, Role role, UUID companyId) {
        PasswordPolicy.validate(password);
        String normalizedEmail = AuthService.normalizeEmail(email);
        if (users.existsByEmail(normalizedEmail)) {
            throw new ApiException(HttpStatus.CONFLICT, "Email is already registered");
        }
        if ((role == Role.ADMIN) != (companyId == null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Administrators have no company; other roles require one");
        }
        Company company = companyId == null ? null : companies.findById(companyId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Company not found"));
        return CurrentUser.from(users.save(new AppUser(normalizedEmail, passwords.encode(password), role, company)));
    }

    @Transactional
    public UserStatus setActive(UUID id, boolean active, UUID actorId) {
        if (id.equals(actorId) && !active) {
            throw new ApiException(HttpStatus.CONFLICT, "Cannot deactivate your own administrator account");
        }
        AppUser user = users.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        user.setActive(active);
        return new UserStatus(user.getId(), user.isActive());
    }

    public record UserStatus(UUID id, boolean active) {}
}
