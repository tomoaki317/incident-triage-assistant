package com.example.triage.ai;

import java.time.Duration;

/** Test seam: bodies and headers must never be logged. */
public interface OpenAiTransport {
    record Reply(int status, String body) {
        @Override public String toString() { return "Reply[redacted]"; }
    }
    Reply post(String path, String body, String apiKey, Duration timeout);
}
