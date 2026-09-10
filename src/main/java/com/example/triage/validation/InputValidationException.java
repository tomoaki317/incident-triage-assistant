package com.example.triage.validation;

import java.util.Map;

public final class InputValidationException extends IllegalArgumentException {
    private final Map<String, String> fieldErrors;

    public InputValidationException(Map<String, String> fieldErrors) {
        super("入力内容を確認してください。");
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public Map<String, String> fieldErrors() { return fieldErrors; }
}
