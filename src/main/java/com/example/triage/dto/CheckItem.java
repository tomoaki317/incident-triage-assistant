package com.example.triage.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CheckItem(
        @JsonProperty("id") String id,
        @JsonProperty("action") String action,
        @JsonProperty("purpose") String purpose,
        @JsonProperty("priority") Priority priority
) {}
