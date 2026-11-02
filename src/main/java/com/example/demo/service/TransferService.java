package com.example.demo.service;

import com.example.demo.audit.AuditContext;
import com.example.demo.controller.TransferRequest;
import com.example.demo.controller.TransferResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
public class TransferService {

    private static final String TENANT = "demo";

    private final PostingService posting;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    public TransferService(
            PostingService posting,
            AuditService audit,
            JdbcTemplate jdbc) {
        this.posting = posting;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    public TransferResult transfer(String key, TransferRequest request, AuditContext auditContext) {
        validate(key, request);

        String requestHash = fingerprint(request);

        try {
            return posting.post(key, requestHash, request, auditContext);

        } catch (DuplicateKeyException duplicate) {
            /*
             * The transactional PostingService call has ended and rolled back.
             * Now read the committed result belonging to the winning request.
             */
            List<Map<String, Object>> matches = jdbc.queryForList("""
                SELECT request_hash, transfer_id
                FROM demo_idempotency
                WHERE tenant_id = ? AND idempotency_key = ?
                """, TENANT, key);

            if (matches.isEmpty()) {
                // Duplicate may have come from another unique constraint.
                throw duplicate;
            }

            Map<String, Object> existing = matches.get(0);
            String previousHash = (String) existing.get("request_hash");

            if (!requestHash.equals(previousHash)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Idempotency key used with a different request");
            }

            String existingTransferId =
                    (String) existing.get("transfer_id");

            audit.recordRetry(auditContext, existingTransferId, key);

            return new TransferResult(
                    existingTransferId,
                    "REPLAYED",
                    true);
        }
    }

    private static void validate(
            String key,
            TransferRequest request) {

        if (key == null || key.isBlank() || key.length() > 100) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid Idempotency-Key");
        }

        if (request == null
                || request.sourceAccount() == null
                || request.targetAccount() == null
                || request.sourceAccount().isBlank()
                || request.targetAccount().isBlank()
                || request.sourceAccount().equals(request.targetAccount())
                || request.amountMinor() <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid transfer");
        }
    }

    private static String fingerprint(TransferRequest request) {
        try {
            // Length prefixes avoid ambiguity in concatenated values.
            String source = request.sourceAccount();
            String target = request.targetAccount();

            String canonical =
                    source.length() + ":" + source
                            + target.length() + ":" + target
                            + ":" + request.amountMinor();

            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(bytes);

        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not fingerprint transfer request", e);
        }
    }

    public List<Map<String, Object>> accounts() {
        return jdbc.queryForList("""
            SELECT id, currency, opening_minor, balance_minor
            FROM demo_accounts
            ORDER BY id
            """);
    }

    public List<Map<String, Object>> entries(String transferId) {
        return jdbc.queryForList("""
            SELECT transfer_id, account_id, side,
                   amount_minor, currency, created_at
            FROM demo_ledger_entries
            WHERE transfer_id = ?
            ORDER BY id
            """, transferId);
    }

    public List<Map<String, Object>> audit() {
        return jdbc.queryForList("""
        SELECT event_id, event_type, outcome, reason_code,
               tenant_id, actor_type, actor_id,
               request_id, transfer_id, recorded_at
        FROM demo_audit_events_v2
        ORDER BY recorded_at DESC, event_id DESC
        LIMIT 100
        """);
    }
}