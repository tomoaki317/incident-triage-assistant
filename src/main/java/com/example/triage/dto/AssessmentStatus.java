package com.example.triage.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum AssessmentStatus {
    @JsonProperty("hypotheses_available") HYPOTHESES_AVAILABLE,
    @JsonProperty("insufficient_information") INSUFFICIENT_INFORMATION,
    @JsonProperty("out_of_scope") OUT_OF_SCOPE
}
