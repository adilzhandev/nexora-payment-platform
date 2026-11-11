package com.nexora.api.auth;

import com.nexora.api.company.CompanyRepository;
import com.nexora.api.error.ApiException;
import com.nexora.api.user.Role;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TenantResolver {
    private final CompanyRepository companies;

    public TenantResolver(CompanyRepository companies) { this.companies = companies; }

    @Transactional(readOnly = true)
    public UUID resolve(CurrentUser user, UUID selectedCompany) {
        UUID companyId = user.companyId();
        if (user.role() == Role.ADMIN) {
            companyId = selectedCompany;
            if (companyId == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Administrators must supply X-Company-Id");
            }
        } else if (selectedCompany != null && !selectedCompany.equals(companyId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Company access denied");
        }
        if (companyId == null || !companies.findById(companyId).map(company -> company.isActive()).orElse(false)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Company is unavailable");
        }
        return companyId;
    }
}
