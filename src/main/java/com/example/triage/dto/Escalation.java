package com.example.triage.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record Escalation(
        @JsonProperty("summary") String summary,
        @JsonProperty("occurred_at") String occurredAt,
        @JsonProperty("environment") String environment,
        @JsonProperty("impact") String impact,
        @JsonProperty("ongoing_status") String ongoingStatus,
        @JsonProperty("destination") String destination,
        @JsonProperty("related_fact_ids") List<String> relatedFactIds,
        @JsonProperty("hypothesis_ids") List<String> hypothesisIds,
        @JsonProperty("checks_performed") List<String> checksPerformed,
        @JsonProperty("open_questions") List<String> openQuestions
) {}
