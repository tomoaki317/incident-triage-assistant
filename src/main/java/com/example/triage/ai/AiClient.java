package com.example.triage.ai;

import com.example.triage.dto.MaskedIncidentInput;

/** Accepts only the reviewed, masked input; returns untrusted JSON for validation. */
public interface AiClient {
    String analyze(MaskedIncidentInput input);
    default AiResponse analyze(MaskedIncidentInput input, AiCallOptions options) {
        return new AiResponse(analyze(input), null, null);
    }
}
