package com.example.demo.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class AuditEventWriter {

    private final JdbcTemplate jdbc;

    public AuditEventWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String append(
            String eventType,
            String outcome,
            String reasonCode,
            String tenantId,
            String actorType,
            String actorId,
            String requestId,
            String transferId,
            String idempotencyKey
    ) {
        String eventId = UUID.randomUUID().toString();

        jdbc.update("""
            INSERT INTO demo_audit_events_v2
                (event_id, event_type, outcome, reason_code,
                 tenant_id, actor_type, actor_id,
                 request_id, transfer_id, idempotency_key_hash)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
                eventId,
                eventType,
                outcome,
                reasonCode,
                tenantId,
                actorType,
                actorId,
                requestId,
                transferId,
                idempotencyKey == null ? null : sha256(idempotencyKey)
        );

        jdbc.update("""
            INSERT INTO demo_audit_outbox (event_id)
            VALUES (?)
            """, eventId);

        return eventId;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot hash audit reference", e);
        }
    }
}