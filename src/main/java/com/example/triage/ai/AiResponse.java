package com.example.triage.ai;

/** Null usage means unknown, never zero cost. */
public record AiResponse(String json, Long inputTokens, Long outputTokens, java.math.BigDecimal actualCostUsd) {
    public AiResponse(String json, Long inputTokens, Long outputTokens) { this(json, inputTokens, outputTokens, null); }
    @Override public String toString() { return "AiResponse[redacted]"; }
}
