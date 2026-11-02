package com.example.demo.controller;

import com.example.demo.audit.AuditContext;
import com.example.demo.audit.DemoRequestIdFilter;
import com.example.demo.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@RestControllerAdvice(assignableTypes = TransferController.class)
public class TransferExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(TransferExceptionHandler.class);

    private final AuditService auditService;

    public TransferExceptionHandler(AuditService attemptAuditService) {
        this.auditService = attemptAuditService;
    }

    /**
     * Handles business/API errors deliberately raised by TransferService
     * or PostingService: insufficient funds, idempotency conflict, etc.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleExpected(
            ResponseStatusException exception,
            HttpServletRequest request
    ) {
        String requestId = requestId(request);
        int status = exception.getStatusCode().value();
        String reasonCode = classify(exception);

        auditFailureSafely(request, requestId, reasonCode);

        return ResponseEntity
                .status(exception.getStatusCode())
                .header("X-Request-Id", requestId)
                .body(Map.of(
                        "requestId", requestId,
                        "error", reasonCode
                ));
    }

    /**
     * Invalid JSON may fail before TransferController.transfer() is called.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleInvalidJson(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        return badRequest(request, "INVALID_JSON");
    }

    /**
     * Missing Idempotency-Key may also fail before the controller runs.
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, String>> handleMissingHeader(
            MissingRequestHeaderException exception,
            HttpServletRequest request
    ) {
        return badRequest(request, "MISSING_REQUIRED_HEADER");
    }

    /**
     * Last-resort handling for unexpected application errors.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(
            Exception exception,
            HttpServletRequest request
    ) {
        String requestId = requestId(request);

        log.error("Unexpected transfer API error; requestId={}",
                requestId, exception);

        auditFailureSafely(request, requestId, "UNEXPECTED_ERROR");

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .header("X-Request-Id", requestId)
                .body(Map.of(
                        "requestId", requestId,
                        "error", "UNEXPECTED_ERROR"
                ));
    }

    private ResponseEntity<Map<String, String>> badRequest(
            HttpServletRequest request,
            String reasonCode
    ) {
        String requestId = requestId(request);

        auditFailureSafely(request, requestId, reasonCode);

        return ResponseEntity
                .badRequest()
                .header("X-Request-Id", requestId)
                .body(Map.of(
                        "requestId", requestId,
                        "error", reasonCode
                ));
    }

    private void auditFailureSafely(
            HttpServletRequest request,
            String requestId,
            String reasonCode
    ) {
        // This advice is scoped to TransferController, which also has GET
        // endpoints. Audit only failed POST /demo/transfers attempts.
        if (!"POST".equalsIgnoreCase(request.getMethod())
                || !"/demo/transfers".equals(request.getServletPath())) {
            return;
        }

        AuditContext context = new AuditContext(
                "demo",
                "SERVICE",
                "local-demo-client",
                requestId
        );

        String key = request.getHeader("Idempotency-Key");

        try {
            // AttemptAuditService must be a separate Spring bean, with
            // recordFailure() annotated REQUIRES_NEW.
            auditService.recordFailure(context, reasonCode, key);
        } catch (Exception auditException) {
            // Do not replace the original API error with an audit-write error.
            // But make the missing audit evidence operationally visible.
            log.error("FAILED TO PERSIST failure audit; requestId={}",
                    requestId, auditException);
        }
    }

    private static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(DemoRequestIdFilter.ATTRIBUTE);

        if (value instanceof String id && !id.isBlank()) {
            return id;
        }

        // Fallback if the filter was not registered correctly.
        String generated = UUID.randomUUID().toString();
        request.setAttribute(DemoRequestIdFilter.ATTRIBUTE, generated);
        return generated;
    }

    private static String classify(ResponseStatusException exception) {
        String reason = exception.getReason();

        if ("Insufficient funds".equals(reason)) {
            return "INSUFFICIENT_FUNDS";
        }
        if ("Cross-currency transfers are not supported".equals(reason)) {
            return "CURRENCY_MISMATCH";
        }
        if (reason != null
                && reason.contains("Idempotency key belongs to a different request")) {
            return "IDEMPOTENCY_CONFLICT";
        }
        if (exception.getStatusCode().value() == 400) {
            return "INVALID_TRANSFER";
        }
        if (exception.getStatusCode().value() == 409) {
            return "CONFLICT";
        }

        return "REQUEST_FAILED";
    }
}