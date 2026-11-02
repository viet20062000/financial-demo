package com.example.demo.service;

import com.example.demo.audit.AuditContext;
import com.example.demo.audit.AuditEventWriter;
import com.example.demo.controller.TransferRequest;
import com.example.demo.controller.TransferResult;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PostingService {

    private static final String TENANT = "demo";
    private final JdbcTemplate jdbc;
    private final AuditEventWriter auditWriter;

    public PostingService(JdbcTemplate jdbc, AuditEventWriter auditWriter) {
        this.jdbc = jdbc;
        this.auditWriter = auditWriter;
    }

    @Transactional
    public TransferResult post(
            String key,
            String requestHash,
            TransferRequest request,
            AuditContext auditContext) {

        // Lock both accounts in a consistent order to reduce deadlocks.
        List<String> accountIds = List.of(
                request.sourceAccount(),
                request.targetAccount()
        ).stream().sorted().toList();

        for (String id : accountIds) {
            List<Map<String, Object>> accounts = jdbc.queryForList("""
                SELECT id
                FROM demo_accounts
                WHERE id = ?
                FOR UPDATE
                """, id);

            if (accounts.isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Unknown account: " + id);
            }
        }

        Map<String, Object> source = jdbc.queryForMap("""
            SELECT currency, balance_minor
            FROM demo_accounts
            WHERE id = ?
            """, request.sourceAccount());

        Map<String, Object> target = jdbc.queryForMap("""
            SELECT currency, balance_minor
            FROM demo_accounts
            WHERE id = ?
            """, request.targetAccount());

        String currency = (String) source.get("currency");

        if (!currency.equals(target.get("currency"))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Cross-currency transfers are not supported");
        }

        long sourceBalance = ((Number) source.get("balance_minor")).longValue();
        long targetBalance = ((Number) target.get("balance_minor")).longValue();

        if (sourceBalance < request.amountMinor()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Insufficient funds");
        }

        // Avoid overflowing a BIGINT balance projection.
        try {
            Math.addExact(targetBalance, request.amountMinor());
        } catch (ArithmeticException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Target balance would overflow");
        }

        String transferId = UUID.randomUUID().toString();

        jdbc.update("""
            INSERT INTO demo_transfers
                (id, source_account, target_account, amount_minor, currency)
            VALUES (?, ?, ?, ?, ?)
            """,
                transferId,
                request.sourceAccount(),
                request.targetAccount(),
                request.amountMinor(),
                currency);

        // Two equal entries: debits = credits.
        // Wallet balances are modeled as platform liabilities.
        jdbc.update("""
            INSERT INTO demo_ledger_entries
                (transfer_id, account_id, side, amount_minor, currency)
            VALUES (?, ?, 'DEBIT', ?, ?)
            """,
                transferId,
                request.sourceAccount(),
                request.amountMinor(),
                currency);

        jdbc.update("""
            INSERT INTO demo_ledger_entries
                (transfer_id, account_id, side, amount_minor, currency)
            VALUES (?, ?, 'CREDIT', ?, ?)
            """,
                transferId,
                request.targetAccount(),
                request.amountMinor(),
                currency);

        // These balances are projections, not independent source-of-truth edits.
        jdbc.update("""
            UPDATE demo_accounts
            SET balance_minor = balance_minor - ?
            WHERE id = ?
            """, request.amountMinor(), request.sourceAccount());

        jdbc.update("""
            UPDATE demo_accounts
            SET balance_minor = balance_minor + ?
            WHERE id = ?
            """, request.amountMinor(), request.targetAccount());

        // The unique primary key is the concurrency guard.
        // This row commits atomically with the transfer and ledger entries.
        jdbc.update("""
            INSERT INTO demo_idempotency
                (tenant_id, idempotency_key, request_hash, transfer_id)
            VALUES (?, ?, ?, ?)
            """, TENANT, key, requestHash, transferId);

        auditWriter.append(
                "TRANSFER_POSTED",
                "SUCCEEDED",
                null,
                auditContext.tenantId(),
                auditContext.actorType(),
                auditContext.actorId(),
                auditContext.requestId(),
                transferId,
                key
        );

        return new TransferResult(transferId, "CREATED", false);
    }
}