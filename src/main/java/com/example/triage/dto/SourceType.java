package com.example.triage.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum SourceType {
    @JsonProperty("user_report") USER_REPORT,
    @JsonProperty("log") LOG
}
