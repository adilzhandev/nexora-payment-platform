package com.nexora.core.refund;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRepository extends JpaRepository<Refund, UUID> {
    List<Refund> findByPaymentIdAndCompanyIdOrderByCreatedAtAsc(UUID paymentId, UUID companyId);
}
