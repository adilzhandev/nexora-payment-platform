package com.nexora.api.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TokenRepository extends JpaRepository<AccessToken, String> {
    @Override
    @EntityGraph(attributePaths = {"user", "user.company"})
    Optional<AccessToken> findById(String tokenHash);
}
