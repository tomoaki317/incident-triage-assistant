package com.example.triage.ai;

/** Null usage means unknown, never zero cost. */
public record AiResponse(String json, Long inputTokens, Long outputTokens) {
    @Override public String toString() { return "AiResponse[redacted]"; }
}
