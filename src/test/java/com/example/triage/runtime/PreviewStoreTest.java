package com.example.triage.runtime;

import com.example.triage.dto.*;
import com.example.triage.masking.MaskingService;
import com.example.triage.service.PreviewService;
import com.example.triage.validation.InputValidator;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreviewStoreTest {
    private final MutableClock clock = new MutableClock();
    private final PreviewStore store = new PreviewStore(clock);
    private final PreviewService service = new PreviewService(new InputValidator(), new MaskingService(), store);
    private PreviewResponse create(String text) {
        return service.create(new IncidentInput(text, "password=synthetic-secret", null, null), "owner");
    }

    @Test void issuesRandomUuidAndExactExpiry() {
        var response = create("障害");
        var uuid = UUID.fromString(response.previewId());
        assertEquals(4, uuid.version());
        assertEquals(2, uuid.variant());
        assertEquals(clock.instant().plusSeconds(300), response.expiresAt());
    }

    @Test void retrievalReturnsTheExactReviewedImmutableContent() {
        var response = create("障害");
        var stored = service.get(response.previewId(), "owner");
        assertSame(response, stored.response());
        assertEquals("password=[MASKED_1]", stored.response().maskedInput().log());
        assertThrows(UnsupportedOperationException.class, () -> stored.response().logLineIds().add("log:L2"));
        assertThrows(UnsupportedOperationException.class, () -> stored.sources().inputFieldIds().add("context.impact"));
    }

    @Test void storesNoRawSecretOrSubstitutionMap() {
        var response = create("email person@example.test");
        String serialized = new tools.jackson.databind.json.JsonMapper().writeValueAsString(service.get(response.previewId(), "owner"));
        assertFalse(serialized.contains("person@example.test"));
        assertFalse(serialized.contains("synthetic-secret"));
        assertTrue(serialized.contains("[MASKED_"));
        assertFalse(service.get(response.previewId(), "owner").toString().contains("email"));
    }

    @Test void missingIdRejected() {
        assertThrows(PreviewUnavailableException.class, () -> service.get(UUID.randomUUID().toString(), "owner"));
    }

    @Test void availableImmediatelyBeforeExpiry() {
        var response = create("障害");
        clock.now = response.expiresAt().minusNanos(1);
        assertEquals(response, service.get(response.previewId(), "owner").response());
    }

    @Test void expiredContentIsCleanedUpWithoutRetrieval() {
        var response = create("障害");
        clock.now = response.expiresAt();
        store.removeExpired();
        clock.now = clock.now.minusSeconds(1);
        assertThrows(PreviewUnavailableException.class, () -> service.get(response.previewId(), "owner"));
    }

    @Test void unavailableAtExactExpiryAndRemoved() {
        var response = create("障害");
        clock.now = response.expiresAt();
        assertThrows(PreviewUnavailableException.class, () -> service.get(response.previewId(), "owner"));
        clock.now = clock.now.minusSeconds(1);
        assertThrows(PreviewUnavailableException.class, () -> service.get(response.previewId(), "owner"));
    }

    @Test void anotherSessionCannotReadOrDelete() {
        var response = create("障害");
        assertThrows(PreviewUnavailableException.class, () -> service.get(response.previewId(), "other"));
        assertThrows(PreviewUnavailableException.class, () -> service.delete(response.previewId(), "other"));
        assertThrows(PreviewUnavailableException.class, () -> service.get(response.previewId(), null));
        assertEquals(response, service.get(response.previewId(), "owner").response());
    }

    @Test void multiplePreviewsRemainIndependent() {
        var a = create("障害A"); var b = create("障害B");
        assertNotEquals(a.previewId(), b.previewId());
        assertEquals("障害A", service.get(a.previewId(), "owner").response().maskedInput().symptom());
        assertEquals("障害B", service.get(b.previewId(), "owner").response().maskedInput().symptom());
    }

    @Test void deletionPreventsSubsequentUse() {
        var response = create("障害");
        service.delete(response.previewId(), "owner");
        assertThrows(PreviewUnavailableException.class, () -> service.get(response.previewId(), "owner"));
    }

    @Test void capacityIsBoundedAndExpiredEntriesReclaimed() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 100; i++) assertTrue(ids.add(create("障害").previewId()));
        assertThrows(PreviewCapacityException.class, () -> create("障害"));
        clock.now = clock.now.plusSeconds(300);
        assertNotNull(create("新規").previewId());
    }

    @Test void sourceFieldsDistinguishMissingFromExplicitUnknown() {
        var input = new IncidentInput("障害", null, "未取得",
                new IncidentContext("不明", null, "", null, null, null, null));
        var response = service.create(input, "owner");
        var snapshot = service.get(response.previewId(), "owner");
        assertEquals(Set.of("symptom", "log_status", "context.occurred_at"), snapshot.sources().inputFieldIds());
        assertTrue(snapshot.sources().logLineIds().isEmpty());
        assertEquals("ai-disconnected-v1", snapshot.aiSettingsVersion());
    }

    @Test void concurrentCreationCannotExceedCapacity() throws Exception {
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<java.util.concurrent.Callable<Boolean>>();
            for (int i = 0; i < 120; i++) tasks.add(() -> {
                try { create("障害"); return true; } catch (PreviewCapacityException e) { return false; }
            });
            int accepted = 0;
            for (var result : executor.invokeAll(tasks)) if (result.get()) accepted++;
            assertEquals(100, accepted);
        }
    }

    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-10T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
