package com.example.triage.ai;

import java.time.Duration;
import java.math.BigDecimal;

/** Implementations must apply timeout, output cap and zero automatic retries. */
public record AiCallOptions(Duration timeout, int maxOutputTokens, BigDecimal maxCostUsd) {
    public int maxRetries() { return 0; }
}
