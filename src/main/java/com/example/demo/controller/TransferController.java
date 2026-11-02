package com.example.demo.controller;

import com.example.demo.audit.AuditContext;
import com.example.demo.audit.DemoRequestIdFilter;
import com.example.demo.service.LedgerExplorerService;
import com.example.demo.service.TransferService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/demo")
public class TransferController {

    private final TransferService service;
    private final LedgerExplorerService ledgerExplorerService;

    public TransferController(TransferService service, LedgerExplorerService ledgerExplorerService) {
        this.service = service;
        this.ledgerExplorerService = ledgerExplorerService;
    }

    @PostMapping("/transfers")
    public ResponseEntity<TransferResult> transfer(
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody TransferRequest request,
            HttpServletRequest httpRequest) {
        String requestId =
                (String) httpRequest.getAttribute(
                        DemoRequestIdFilter.ATTRIBUTE
                );

        AuditContext auditContext = new AuditContext(
                "demo",
                "SERVICE",
                "local-demo-client",
                requestId
        );
        TransferResult result = service.transfer(key, request, auditContext);

        return ResponseEntity.status(
                result.replayed() ? HttpStatus.OK : HttpStatus.CREATED
        ).body(result);
    }

    @GetMapping("/accounts")
    public List<Map<String, Object>> accounts() {
        return service.accounts();
    }

    @GetMapping("/transfers/{id}/entries")
    public List<Map<String, Object>> entries(@PathVariable String id) {
        return service.entries(id);
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> audit() {
        return service.audit();
    }

    @GetMapping("/entries")
    public LedgerPage entries(
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String transferId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) Long beforeId
    ) {
        return ledgerExplorerService.findEntries(accountId, transferId, limit, beforeId);
    }


}

