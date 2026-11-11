package com.nexora.core.ledger;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {
    Page<LedgerTransaction> findByCompanyId(UUID companyId, Pageable pageable);

    @Query("select distinct t from LedgerTransaction t join fetch t.entries e join fetch e.account where t.id in :ids")
    List<LedgerTransaction> findWithEntries(@Param("ids") List<UUID> ids);
}
