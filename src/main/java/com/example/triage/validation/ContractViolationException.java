package com.example.triage.validation;

/** Fixed messages only: parser/schema diagnostics can contain the input body. */
public final class ContractViolationException extends IllegalArgumentException {
    public enum Code { INVALID_JSON, INVALID_STRUCTURE, INVALID_REFERENCE }

    private final Code code;

    public ContractViolationException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
