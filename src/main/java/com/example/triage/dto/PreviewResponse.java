package com.example.triage.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** The stored response is also the content reviewed by the user. No AI is called. */
public record PreviewResponse(
        @JsonProperty("masked_input") MaskedIncidentInput maskedInput,
        @JsonProperty("log_line_ids") List<String> logLineIds,
        String destination, String purpose, List<String> warnings,
        @JsonProperty("preview_id") String previewId,
        @JsonProperty("expires_at") java.time.Instant expiresAt) {
    public PreviewResponse(MaskedIncidentInput input, List<String> lines, String destination,
            String purpose, List<String> warnings) {
        this(input, lines, destination, purpose, warnings, null, null);
    }
    public PreviewResponse {
        logLineIds = List.copyOf(logLineIds);
        warnings = List.copyOf(warnings);
    }
    @Override public String toString() { return "PreviewResponse[redacted]"; }
}
