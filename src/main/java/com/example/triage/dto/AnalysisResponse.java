package com.example.triage.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AnalysisResponse(@JsonProperty("request_id") String requestId, TriageResult result) {
    @Override public String toString() { return "AnalysisResponse[redacted]"; }
}
