package com.example.triage.runtime;

import com.example.triage.dto.PreviewResponse;
import com.example.triage.validation.SourceReferences;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Single-process, bounded storage. No raw input or substitution map is accepted. */
@Component
public class PreviewStore {
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final int CAPACITY = 100;
    private final Clock clock;
    private final Map<String, Entry> entries = new HashMap<>();

    public PreviewStore() { this(Clock.systemUTC()); }
    public PreviewStore(Clock clock) { this.clock = clock; }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60000)
    public synchronized void removeExpired() {
        var now = clock.instant();
        entries.values().removeIf(e -> !now.isBefore(e.snapshot.response().expiresAt()));
    }

    public record Snapshot(PreviewResponse response, SourceReferences sources, String aiSettingsVersion) {
        @Override public String toString() { return "Snapshot[redacted]"; }
    }
    private record Entry(String owner, Snapshot snapshot) {
        @Override public String toString() { return "Entry[redacted]"; }
    }

    public synchronized PreviewResponse save(PreviewResponse prepared, SourceReferences sources, String owner) {
        if (owner == null || owner.isBlank()) throw new PreviewUnavailableException();
        var now = clock.instant();
        entries.values().removeIf(e -> !now.isBefore(e.snapshot.response().expiresAt()));
        if (entries.size() >= CAPACITY) throw new PreviewCapacityException();
        String id;
        do { id = UUID.randomUUID().toString(); } while (entries.containsKey(id));
        var response = new PreviewResponse(prepared.maskedInput(), prepared.logLineIds(),
                prepared.destination(), prepared.purpose(), prepared.warnings(), id, now.plus(TTL));
        entries.put(id, new Entry(owner, new Snapshot(response, sources, "ai-disconnected-v1")));
        return response;
    }

    public synchronized Snapshot get(String id, String owner) {
        var entry = entries.get(id);
        if (entry == null) throw new PreviewUnavailableException();
        if (!clock.instant().isBefore(entry.snapshot.response().expiresAt())) {
            entries.remove(id);
            throw new PreviewUnavailableException();
        }
        if (!entry.owner.equals(owner)) throw new PreviewUnavailableException();
        return entry.snapshot;
    }

    public synchronized void delete(String id, String owner) {
        get(id, owner);
        entries.remove(id);
    }
}
