package com.example.demo.controller;

import com.example.demo.service.LedgerExplorerService;

import java.util.List;

public record LedgerPage(
        List<LedgerExplorerService.LedgerEntry> entries,
        Long nextBeforeId,
        boolean hasMore
) {}