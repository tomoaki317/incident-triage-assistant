package com.example.triage.ai;

/** Adapters discard external bodies and causes when translating provider failures. */
public final class AiFailure extends RuntimeException {
    public enum Kind { PROVIDER_UNAVAILABLE, TIMEOUT }
    private final Kind kind;
    public AiFailure(Kind kind) { super("AI request failed"); this.kind = kind; }
    public Kind kind() { return kind; }
    public static AiFailure providerStatus(int status) {
        if (status != 429 && (status < 500 || status > 599)) throw new IllegalArgumentException("Unsupported provider status");
        return new AiFailure(Kind.PROVIDER_UNAVAILABLE);
    }
}
