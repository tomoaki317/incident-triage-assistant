package com.example.triage.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record Fact(
        @JsonProperty("id") String id,
        @JsonProperty("statement") String statement,
        @JsonProperty("source_type") SourceType sourceType,
        @JsonProperty("source_ref") String sourceRef
) {}
