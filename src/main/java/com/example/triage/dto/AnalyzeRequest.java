package com.example.triage.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AnalyzeRequest(@JsonProperty("preview_id") String previewId,
        @JsonProperty("execution_id") String executionId) {}
