package com.example.triage.ai;

import com.example.triage.dto.MaskedIncidentInput;

/** A real implementation must count the complete model request, including instructions/schema. */
public interface TokenCounter {
    long count(MaskedIncidentInput input);
}
