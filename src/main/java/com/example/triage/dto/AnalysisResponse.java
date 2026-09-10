package com.example.triage.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AnalysisResponse(@JsonProperty("request_id") String requestId, TriageResult result,
        @JsonProperty("execution_id") String executionId) {
    public AnalysisResponse(String requestId, TriageResult result) { this(requestId, result, null); }
    @Override public String toString() { return "AnalysisResponse[redacted]"; }
}
