package com.example.demo.audit;

public record AuditContext(
        String tenantId,
        String actorType,
        String actorId,
        String requestId
) {}