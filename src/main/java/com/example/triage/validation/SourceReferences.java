package com.example.triage.validation;

import java.util.Set;

/** Supplied by the caller from actual input, never from the generated response. */
public record SourceReferences(Set<String> inputFieldIds, Set<String> logLineIds) {
    private static final Set<String> INPUT_FIELDS = Set.of(
            "symptom", "log_status", "context.occurred_at", "context.environment",
            "context.impact", "context.ongoing_status", "context.recent_changes",
            "context.checks_performed", "context.destination");

    public SourceReferences {
        inputFieldIds = Set.copyOf(inputFieldIds);
        logLineIds = Set.copyOf(logLineIds);
        if (!INPUT_FIELDS.containsAll(inputFieldIds)
                || logLineIds.stream().anyMatch(id -> !id.matches("log:L[1-9][0-9]*"))) {
            throw new IllegalArgumentException("Invalid source reference configuration");
        }
    }
}
