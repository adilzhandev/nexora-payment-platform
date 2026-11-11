package com.nexora.api.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService service;

    public AuthController(AuthService service) { this.service = service; }

    @PostMapping("/register")
    @SecurityRequirements
    @ResponseStatus(HttpStatus.CREATED)
    public CurrentUser register(@Valid @RequestBody Registration request) {
        return service.register(request.email(), request.password(), request.companyName(), request.registrationNumber());
    }

    @PostMapping("/login")
    @SecurityRequirements
    public AuthService.LoginResponse login(@Valid @RequestBody Login request) {
        return service.login(request.email(), request.password());
    }

    @GetMapping("/me")
    public CurrentUser me(@AuthenticationPrincipal CurrentUser user) { return user; }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestHeader("Authorization") String authorization) {
        service.logout(authorization.substring(7));
    }

    public record Registration(@NotBlank @Email @Size(max = 254) String email,
                               @NotBlank @Size(min = 12, max = 64) String password,
                               @NotBlank @Size(max = 200) String companyName,
                               @NotBlank @Size(max = 64) String registrationNumber) {}

    public record Login(@NotBlank @Email @Size(max = 254) String email,
                        @NotBlank @Size(max = 64) String password) {}
}
