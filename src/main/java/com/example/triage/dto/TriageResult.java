package com.example.triage.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record TriageResult(
        @JsonProperty("schema_version") String schemaVersion,
        @JsonProperty("assessment_status") AssessmentStatus assessmentStatus,
        @JsonProperty("assessment_reason") String assessmentReason,
        @JsonProperty("summary") String summary,
        @JsonProperty("facts") List<Fact> facts,
        @JsonProperty("hypotheses") List<Hypothesis> hypotheses,
        @JsonProperty("checks") List<CheckItem> checks,
        @JsonProperty("missing_information") List<MissingInformation> missingInformation,
        @JsonProperty("escalation") Escalation escalation,
        @JsonProperty("references") List<Void> references
) {}
