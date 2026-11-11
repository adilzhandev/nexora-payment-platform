package com.nexora.api.company;

import java.util.UUID;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface CompanyRepository extends JpaRepository<Company, UUID> {
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select c from Company c where c.id = :id")
    Optional<Company> findLockedForCacheById(UUID id);
}
