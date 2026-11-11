package com.nexora.api.company;

import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompanyServiceTest {
    private final CompanyRepository repository = mock(CompanyRepository.class);
    private final CompanyCache cache = mock(CompanyCache.class);
    private final CompanyService service = new CompanyService(repository, cache);

    @AfterEach
    void cleanUp() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void profileUpdateInvalidatesCacheOnlyAfterCommit() {
        Company company = new Company("Old", "123");
        when(repository.findById(company.getId())).thenReturn(Optional.of(company));
        TransactionSynchronizationManager.initSynchronization();
        var result = service.update(company.getId(), "New", "456");
        assertThat(result.name()).isEqualTo("New");
        verify(cache, never()).evict(company.getId());
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(cache).evict(company.getId());
    }
}
