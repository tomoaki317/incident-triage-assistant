package com.example.triage.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Raw input: never pass this type to an AI client. */
public record IncidentInput(String symptom, String log,
        @JsonProperty("log_status") String logStatus, IncidentContext context) {
    @Override public String toString() { return "IncidentInput[redacted]"; }
}
