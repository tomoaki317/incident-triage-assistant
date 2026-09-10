package com.example.triage.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public record ApiError(@JsonProperty("request_id") String requestId, String code, String message,
        @JsonProperty("field_errors") Map<String, String> fieldErrors) {
    public ApiError {
        fieldErrors = Map.copyOf(fieldErrors);
    }
}
