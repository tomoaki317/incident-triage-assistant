package com.example.triage.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Only masked and normalized values belong in the outbound preview. */
public record MaskedIncidentInput(String symptom, String log,
        @JsonProperty("log_status") String logStatus, IncidentContext context) {
    @Override public String toString() { return "MaskedIncidentInput[redacted]"; }
}
