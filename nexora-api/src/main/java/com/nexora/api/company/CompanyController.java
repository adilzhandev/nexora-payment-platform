package com.nexora.api.company;

import com.nexora.api.auth.CurrentUser;
import com.nexora.api.auth.TenantResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/companies/me")
public class CompanyController {
    private final CompanyService service;
    private final TenantResolver tenants;

    public CompanyController(CompanyService service, TenantResolver tenants) {
        this.service = service;
        this.tenants = tenants;
    }

    @GetMapping
    public CompanyView get(@AuthenticationPrincipal CurrentUser user,
                           @RequestHeader(value = "X-Company-Id", required = false) UUID companyId) {
        return service.get(tenants.resolve(user, companyId));
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('MERCHANT', 'ADMIN')")
    public CompanyView update(@AuthenticationPrincipal CurrentUser user,
                              @RequestHeader(value = "X-Company-Id", required = false) UUID companyId,
                              @Valid @RequestBody Profile request) {
        return service.update(tenants.resolve(user, companyId), request.name(), request.registrationNumber());
    }

    public record Profile(@NotBlank @Size(max = 200) String name,
                          @NotBlank @Size(max = 64) String registrationNumber) {}
}
