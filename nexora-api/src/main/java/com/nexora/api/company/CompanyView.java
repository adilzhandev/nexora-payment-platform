package com.nexora.api.company;

import java.time.Instant;
import java.util.UUID;

public record CompanyView(UUID id, String name, String registrationNumber, boolean active, long version,
                          Instant createdAt) {
    public static CompanyView from(Company company) {
        return new CompanyView(company.getId(), company.getName(), company.getRegistrationNumber(),
                company.isActive(), company.getVersion(), company.getCreatedAt());
    }
}
