package com.example.triage.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MissingInformation(
        @JsonProperty("item") String item,
        @JsonProperty("reason") String reason
) {}
