package com.example.demo.service;

import com.example.demo.controller.LedgerPage;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class LedgerExplorerService {

    private final JdbcTemplate jdbc;

    public LedgerExplorerService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public LedgerPage findEntries(
            String accountId,
            String transferId,
            int limit,
            Long beforeId
    ) {
        if (limit < 1 || limit > 100) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "limit must be between 1 and 100"
            );
        }

        if (beforeId != null && beforeId < 1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "beforeId must be positive"
            );
        }

        if (accountId != null && accountId.length() > 30) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "accountId is too long"
            );
        }

        if (transferId != null && transferId.length() > 36) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "transferId is too long"
            );
        }

        StringBuilder sql = new StringBuilder("""
            SELECT id,
                   transfer_id,
                   account_id,
                   side,
                   amount_minor,
                   currency,
                   created_at
            FROM demo_ledger_entries
            WHERE 1 = 1
            """);

        List<Object> parameters = new ArrayList<>();

        if (accountId != null && !accountId.isBlank()) {
            sql.append(" AND account_id = ?");
            parameters.add(accountId);
        }

        if (transferId != null && !transferId.isBlank()) {
            sql.append(" AND transfer_id = ?");
            parameters.add(transferId);
        }

        if (beforeId != null) {
            sql.append(" AND id < ?");
            parameters.add(beforeId);
        }

        sql.append(" ORDER BY id DESC LIMIT ?");

        // Fetch one extra row to determine whether another page exists.
        parameters.add(limit + 1);

        List<LedgerEntry> results = jdbc.query(
                sql.toString(),
                (rs, rowNum) -> new LedgerEntry(
                        rs.getLong("id"),
                        rs.getString("transfer_id"),
                        rs.getString("account_id"),
                        rs.getString("side"),
                        rs.getLong("amount_minor"),
                        rs.getString("currency"),
                        rs.getObject("created_at", LocalDateTime.class)
                ),
                parameters.toArray()
        );

        boolean hasMore = results.size() > limit;

        List<LedgerEntry> pageEntries = hasMore
                ? List.copyOf(results.subList(0, limit))
                : List.copyOf(results);

        Long nextBeforeId = hasMore
                ? pageEntries.get(pageEntries.size() - 1).id()
                : null;

        return new LedgerPage(pageEntries, nextBeforeId, hasMore);
    }

    public record LedgerEntry(
            long id,
            String transferId,
            String accountId,
            String side,
            long amountMinor,
            String currency,
            LocalDateTime createdAt
    ) {}


}