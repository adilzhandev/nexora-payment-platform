package com.nexora.audit.history;

import com.nexora.common.event.FinancialEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit/events")
@Validated
@SecurityRequirement(name = "auditBasic")
public class AuditHistoryController {
    private final AuditHistoryRepository repository;

    public AuditHistoryController(AuditHistoryRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @Operation(summary = "Read company audit events",
            description = "Dedicated platform AUDITOR credentials grant cross-company read access. "
                    + "Merchant credentials are not accepted by this service. Company ID is always required.")
    public List<FinancialEvent> list(@RequestParam UUID companyId,
                                    @RequestParam(required = false) UUID entityId,
                                    @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
                                    @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return repository.find(companyId, entityId, page, size);
    }
}
