package com.example.triage.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum Priority {
    @JsonProperty("high") HIGH,
    @JsonProperty("medium") MEDIUM,
    @JsonProperty("low") LOW
}
