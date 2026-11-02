package com.example.demo.controller;

public record TransferRequest(
        String sourceAccount,
        String targetAccount,
        long amountMinor
) {}
