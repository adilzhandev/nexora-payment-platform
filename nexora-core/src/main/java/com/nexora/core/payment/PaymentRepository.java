package com.nexora.core.payment;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    Optional<Payment> findByIdAndCompanyId(UUID id, UUID companyId);
    Page<Payment> findByCompanyId(UUID companyId, Pageable pageable);
    Page<Payment> findByCompanyIdAndStatus(UUID companyId, PaymentStatus status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id and p.companyId = :companyId")
    Optional<Payment> findLocked(@Param("id") UUID id, @Param("companyId") UUID companyId);
}
