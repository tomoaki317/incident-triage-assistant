package com.example.triage.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** No preview ID until the short-lived preview store is implemented. No AI is called. */
public record PreviewResponse(
        @JsonProperty("masked_input") MaskedIncidentInput maskedInput,
        @JsonProperty("log_line_ids") List<String> logLineIds,
        String destination, String purpose, List<String> warnings) {
    public PreviewResponse {
        logLineIds = List.copyOf(logLineIds);
        warnings = List.copyOf(warnings);
    }
    @Override public String toString() { return "PreviewResponse[redacted]"; }
}
