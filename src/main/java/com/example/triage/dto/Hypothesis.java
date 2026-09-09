package com.example.triage.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record Hypothesis(
        @JsonProperty("id") String id,
        @JsonProperty("description") String description,
        @JsonProperty("evidence_fact_ids") List<String> evidenceFactIds,
        @JsonProperty("unverified_assumptions") List<String> unverifiedAssumptions,
        @JsonProperty("check_ids") List<String> checkIds
) {}
