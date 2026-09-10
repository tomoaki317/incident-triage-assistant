package com.example.triage.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record IncidentContext(
        @JsonProperty("occurred_at") String occurredAt, String environment, String impact,
        @JsonProperty("ongoing_status") String ongoingStatus,
        @JsonProperty("recent_changes") String recentChanges,
        @JsonProperty("checks_performed") String checksPerformed, String destination) {
    @Override public String toString() { return "IncidentContext[redacted]"; }
}
