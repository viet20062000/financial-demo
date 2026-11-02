package com.example.demo.controller;

public record TransferResult(
        String transferId,
        String outcome,
        boolean replayed
) {}
