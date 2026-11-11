package com.nexora.api.auth;

import com.nexora.api.user.AppUser;
import com.nexora.api.user.Role;
import java.util.UUID;

public record CurrentUser(UUID id, UUID companyId, String email, Role role) {
    public static CurrentUser from(AppUser user) {
        return new CurrentUser(user.getId(), user.getCompany() == null ? null : user.getCompany().getId(),
                user.getEmail(), user.getRole());
    }
}
