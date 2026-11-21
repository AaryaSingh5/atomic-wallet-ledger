package com.example.wallet.api;

import com.example.wallet.service.ReconciliationService;
import com.example.wallet.service.ReconciliationService.ReconciliationReport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * NOTE: any authenticated user can call this in the demo. In production this belongs behind an
 * admin role / internal network.
 */
@RestController
@RequestMapping("/api/reconcile")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    public ReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @GetMapping
    public ReconciliationReport reconcile() {
        return reconciliationService.run();
    }
}
