package com.nexora.api.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<AppUser, UUID> {
    @EntityGraph(attributePaths = "company")
    Optional<AppUser> findByEmail(String email);
    boolean existsByEmail(String email);
}
