package com.nexora.api.auth;

import com.nexora.api.company.Company;
import com.nexora.api.company.CompanyRepository;
import com.nexora.api.error.ApiException;
import com.nexora.api.user.Role;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TenantResolverTest {
    private final CompanyRepository companies = mock(CompanyRepository.class);
    private final TenantResolver resolver = new TenantResolver(companies);

    @Test
    void merchantCannotSelectAnotherCompany() {
        var user = new CurrentUser(UUID.randomUUID(), UUID.randomUUID(), "merchant@example.test", Role.MERCHANT);
        assertThatThrownBy(() -> resolver.resolve(user, UUID.randomUUID()))
                .isInstanceOf(ApiException.class).hasMessage("Company access denied");
    }

    @Test
    void disabledCompanyCannotBeUsed() {
        Company company = new Company("Disabled", "company-123");
        company.setActive(false);
        when(companies.findById(company.getId())).thenReturn(Optional.of(company));
        var user = new CurrentUser(UUID.randomUUID(), company.getId(), "merchant@example.test", Role.MERCHANT);
        assertThatThrownBy(() -> resolver.resolve(user, null)).isInstanceOf(ApiException.class);
    }

    @Test
    void administratorMustExplicitlySelectAnActiveCompany() {
        Company company = new Company("Active", "company-123");
        when(companies.findById(company.getId())).thenReturn(Optional.of(company));
        var user = new CurrentUser(UUID.randomUUID(), null, "admin@example.test", Role.ADMIN);
        assertThatThrownBy(() -> resolver.resolve(user, null)).isInstanceOf(ApiException.class);
        assertThat(resolver.resolve(user, company.getId())).isEqualTo(company.getId());
    }
}
