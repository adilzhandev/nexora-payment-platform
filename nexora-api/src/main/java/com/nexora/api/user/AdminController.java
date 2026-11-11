package com.nexora.api.user;

import com.nexora.api.auth.CurrentUser;
import com.nexora.api.company.CompanyService;
import com.nexora.api.company.CompanyView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {
    private final UserService users;
    private final CompanyService companies;

    public AdminController(UserService users, CompanyService companies) {
        this.users = users;
        this.companies = companies;
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public CurrentUser createUser(@Valid @RequestBody NewUser request) {
        return users.create(request.email(), request.password(), request.role(), request.companyId());
    }

    @PutMapping("/users/{id}/status")
    public UserService.UserStatus setUserStatus(@PathVariable UUID id, @Valid @RequestBody Status request,
                                                @AuthenticationPrincipal CurrentUser actor) {
        return users.setActive(id, request.active(), actor.id());
    }

    @PutMapping("/companies/{id}/status")
    public CompanyView setCompanyStatus(@PathVariable UUID id, @Valid @RequestBody Status request) {
        return companies.setActive(id, request.active());
    }

    public record NewUser(@NotBlank @Email @Size(max = 254) String email,
                          @NotBlank @Size(min = 12, max = 64) String password,
                          @NotNull Role role, UUID companyId) {}
    public record Status(@NotNull Boolean active) {}
}
