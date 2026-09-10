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

    public AnalysisService(PreviewStore store, AiClient client, TriageResultValidator validator) {
        this.store = store; this.client = client; this.validator = validator;
    }

    public static final class AlreadyRunningException extends RuntimeException {
        public AlreadyRunningException() { super("Analysis already running"); }
    }

    public AnalysisResponse analyze(String id, String owner) {
        if (id == null || id.isBlank() || id.length() > 100)
            throw new InputValidationException(Map.of("preview_id", "有効なプレビューIDを指定してください。"));
        final PreviewStore.Snapshot snapshot;
        synchronized (running) {
            snapshot = store.get(id, owner);
            if (!running.add(id)) throw new AlreadyRunningException();
        }
        try {
            String json = client.analyze(snapshot.response().maskedInput());
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
