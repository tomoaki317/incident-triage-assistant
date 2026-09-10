package com.example.triage.service;

import com.example.triage.ai.AiClient;
import com.example.triage.dto.AnalysisResponse;
import com.example.triage.runtime.*;
import com.example.triage.validation.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AnalysisService {
    private final PreviewStore store;
    private final AiClient client;
    private final TriageResultValidator validator;
    private final Set<String> running = new HashSet<>();
    private final AnalysisLimits limits;
    private final AnalysisControl control;
    private final com.example.triage.ai.TokenCounter counter;

    public AnalysisService(PreviewStore store, AiClient client, TriageResultValidator validator) {
        this(store, client, validator, new AnalysisLimits(), null, new com.example.triage.ai.StubTokenCounter());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AnalysisService(PreviewStore store, AiClient client, TriageResultValidator validator,
            AnalysisLimits limits, AnalysisControl control, com.example.triage.ai.TokenCounter counter) {
        this.store = store; this.client = client; this.validator = validator;
        this.limits = limits; this.control = control == null ? new AnalysisControl(limits) : control; this.counter = counter;
    }

    public static final class AlreadyRunningException extends RuntimeException {
        public AlreadyRunningException() { super("Analysis already running"); }
    }

    public AnalysisResponse analyze(String id, String owner) {
        return analyze(id, owner, null);
    }

    public AnalysisResponse analyze(String id, String owner, String executionId) {
        String execution = executionId == null ? UUID.randomUUID().toString() : executionId;
        try {
            UUID uuid = UUID.fromString(execution);
            if (uuid.version() != 4 || uuid.variant() != 2 || !uuid.toString().equals(execution)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new InputValidationException(Map.of("execution_id", "UUID v4を指定してください。"));
        }
        return control.execute(execution, owner, id, deadline -> {
            var response = analyzeCore(id, owner, deadline);
            return new AnalysisResponse(response.requestId(), response.result(), execution);
        });
    }

    private AnalysisResponse analyzeCore(String id, String owner, long deadline) {
        if (id == null || id.isBlank() || id.length() > 100)
            throw new InputValidationException(Map.of("preview_id", "有効なプレビューIDを指定してください。"));
        final PreviewStore.Snapshot snapshot;
        synchronized (running) {
            snapshot = store.get(id, owner);
            if (!running.add(id)) throw new AlreadyRunningException();
        }
        try {
            long tokens = counter.count(snapshot.response().maskedInput());
            if (tokens < 0 || tokens > limits.maxInputTokens)
                throw new InputValidationException(Map.of("preview_id", "入力トークン上限を超えています。入力を抜粋して再確認してください。"));
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new com.example.triage.ai.AiFailure(com.example.triage.ai.AiFailure.Kind.TIMEOUT);
            var options = new com.example.triage.ai.AiCallOptions(java.time.Duration.ofNanos(
                    Math.min(remaining, limits.clientTimeout.toNanos())), limits.maxOutputTokens, limits.maxCostUsd);
            String json = client.analyze(snapshot.response().maskedInput(), options).json();
            var result = validator.validate(json, snapshot.sources());
            return new AnalysisResponse(UUID.randomUUID().toString(), result);
        } finally {
            // The plan requires a fresh preview for reanalysis, including failed attempts.
            synchronized (running) {
                try { store.delete(id, owner); } catch (PreviewUnavailableException ignored) { /* expired/cleared */ }
                running.remove(id);
            }
        }
    }
}
