package com.example.demo.service;

import com.example.demo.audit.AuditContext;
import com.example.demo.audit.AuditEventWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {


    private final AuditEventWriter writer;

    public AuditService(AuditEventWriter writer) {
        this.writer = writer;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRetry(
            AuditContext context,
            String transferId,
            String key
    ) {
        writer.append(
                "TRANSFER_RETRIED",
                "REPLAYED",
                null,
                context.tenantId(),
                context.actorType(),
                context.actorId(),
                context.requestId(),
                transferId,
                key
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(
            AuditContext context,
            String reasonCode,
            String key
    ) {
        writer.append(
                "TRANSFER_ATTEMPT_FAILED",
                "FAILED",
                reasonCode,
                context.tenantId(),
                context.actorType(),
                context.actorId(),
                context.requestId(),
                null,
                key
        );
    }
}