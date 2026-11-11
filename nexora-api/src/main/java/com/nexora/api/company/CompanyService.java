package com.nexora.api.company;

import com.nexora.api.error.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class CompanyService {
    private final CompanyRepository companies;
    private final CompanyCache cache;

    public CompanyService(CompanyRepository companies, CompanyCache cache) {
        this.companies = companies;
        this.cache = cache;
    }

    @Transactional
    public CompanyView get(UUID id) {
        return cache.get(id).orElseGet(() -> {
            Company company = companies.findLockedForCacheById(id)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Company not found"));
            CompanyView result = CompanyView.from(company);
            cache.put(result);
            return result;
        });
    }

    @Transactional
    public CompanyView update(UUID id, String name, String registrationNumber) {
        Company company = find(id);
        company.updateProfile(name.strip(), registrationNumber.strip());
        companies.flush();
        invalidateAfterCommit(id);
        return CompanyView.from(company);
    }

    @Transactional
    public CompanyView setActive(UUID id, boolean active) {
        Company company = find(id);
        company.setActive(active);
        companies.flush();
        invalidateAfterCommit(id);
        return CompanyView.from(company);
    }

    private Company find(UUID id) {
        return companies.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Company not found"));
    }

    private void invalidateAfterCommit(UUID id) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() { cache.evict(id); }
        });
    }
}
