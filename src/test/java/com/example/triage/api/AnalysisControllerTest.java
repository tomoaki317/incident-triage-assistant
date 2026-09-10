package com.example.triage.api;

import com.example.triage.ai.*;
import com.example.triage.dto.*;
import com.example.triage.masking.MaskingService;
import com.example.triage.runtime.*;
import com.example.triage.service.*;
import com.example.triage.validation.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AnalysisControllerTest {
    private final MutableClock clock = new MutableClock();
    private final PreviewStore store = new PreviewStore(clock);
    private final PreviewService previews = new PreviewService(new InputValidator(), new MaskingService(), store);
    private final MockHttpSession session = new MockHttpSession();
    private final TriageResultValidator validator = new TriageResultValidator(new SchemaValidator(), new ReferenceValidator());
    private AnalysisService service(AiClient client) { return new AnalysisService(store, client, validator); }
    private MockMvc mvc(AiClient client) {
        return MockMvcBuilders.standaloneSetup(new AnalysisController(service(client)))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }
    private PreviewResponse preview(String symptom, String log) {
        return previews.create(new IncidentInput(symptom, log, log == null ? "未取得" : null, null), session.getId());
    }
    private String body(String id) { return "{\"preview_id\":\"" + id + "\"}"; }

    @ParameterizedTest @ValueSource(strings = {"hypotheses_available", "insufficient_information", "out_of_scope"})
    void threeStatesPassContractAndDeletePreview(String state) throws Exception {
        var response = preview(state.equals("out_of_scope") ? "ネットワーク機器の侵害調査" : "HTTP 500",
                state.equals("hypotheses_available") ? "ERROR\nDuplicate entry 'sample'" : null);
        mvc(new StubAiClient()).perform(post("/api/analyses").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content(body(response.previewId())))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.result.assessment_status").value(state))
                .andExpect(jsonPath("$.request_id", matchesPattern("[0-9a-f-]{36}")));
        assertThrows(PreviewUnavailableException.class, () -> store.get(response.previewId(), session.getId()));
    }

    @Test void receivesExactMaskedInput() {
        var response = preview("person@example.test", "password=secret\nDuplicate entry");
        service(input -> {
            assertSame(response.maskedInput(), input);
            assertFalse(input.log().contains("secret"));
            return new StubAiClient().analyze(input);
        }).analyze(response.previewId(), session.getId());
    }

    @Test void unknownIdIsGone() throws Exception {
        mvc(input -> { fail("must not call client"); return ""; }).perform(post("/api/analyses").session(session)
                .contentType(MediaType.APPLICATION_JSON).content(body("unknown")))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("PREVIEW_UNAVAILABLE"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test void expiredIdIsGone() throws Exception {
        var response = preview("障害", null); clock.now = response.expiresAt();
        mvc(input -> { fail("must not call client"); return ""; }).perform(post("/api/analyses").session(session)
                .contentType(MediaType.APPLICATION_JSON).content(body(response.previewId())))
                .andExpect(status().isGone());
    }

    @Test void anotherSessionCannotAnalyzeOrDeleteOwnersPreview() throws Exception {
        var response = preview("障害", null);
        mvc(input -> { fail("must not call client"); return ""; }).perform(post("/api/analyses").session(new MockHttpSession())
                .contentType(MediaType.APPLICATION_JSON).content(body(response.previewId())))
                .andExpect(status().isGone());
        assertEquals(response, store.get(response.previewId(), session.getId()).response());
    }

    @Test void missingSessionCannotAnalyze() throws Exception {
        var response = preview("障害", null);
        mvc(new StubAiClient()).perform(post("/api/analyses").contentType(MediaType.APPLICATION_JSON)
                .content(body(response.previewId()))).andExpect(status().isGone());
    }

    @ParameterizedTest @ValueSource(strings = {"json", "reference", "state"})
    void invalidResponsesAreSanitizedAndPreviewDeleted(String failure) throws Exception {
        var response = preview("障害", null);
        AiClient invalid = input -> {
            String valid = new StubAiClient().analyze(input);
            return switch (failure) {
                case "json" -> "private-secret { broken JSON";
                case "reference" -> valid.replace("\"symptom\"", "\"context.impact\"");
                default -> valid.replace("insufficient_information", "hypotheses_available");
            };
        };
        mvc(invalid).perform(post("/api/analyses").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content(body(response.previewId())))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("INVALID_ANALYSIS_RESPONSE"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.result").doesNotExist()).andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(content().string(not(containsString("private-secret"))));
        assertThrows(PreviewUnavailableException.class, () -> store.get(response.previewId(), session.getId()));
    }

    @Test void unexpectedExceptionIsSanitizedAndPreviewDeleted() throws Exception {
        var response = preview("障害", null);
        mvc(input -> { throw new IllegalStateException("private-secret stack details"); })
                .perform(post("/api/analyses").session(session).contentType(MediaType.APPLICATION_JSON).content(body(response.previewId())))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(not(containsString("private-secret"))));
        assertThrows(PreviewUnavailableException.class, () -> store.get(response.previewId(), session.getId()));
    }

    @Test void duplicateConcurrentRequestDoesNotCallStubTwice() throws Exception {
        var response = preview("障害", null);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        var analyses = service(input -> {
            calls.incrementAndGet(); entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
            return new StubAiClient().analyze(input);
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> analyses.analyze(response.previewId(), session.getId()));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertThrows(AnalysisService.AlreadyRunningException.class, () -> analyses.analyze(response.previewId(), session.getId()));
                assertEquals(1, calls.get());
            } finally { release.countDown(); }
            assertNotNull(first.get(5, TimeUnit.SECONDS));
        }
    }

    @Test void missingPreviewIdIsBadRequest() throws Exception {
        mvc(new StubAiClient()).perform(post("/api/analyses").session(session).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field_errors.preview_id").exists());
    }

    @Test void onlyAnalyzedPreviewIsDeleted() {
        var a = preview("障害A", null); var b = preview("障害B", null);
        service(new StubAiClient()).analyze(a.previewId(), session.getId());
        assertEquals(b, store.get(b.previewId(), session.getId()).response());
    }

    private static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-10T00:00:00Z");
        public Instant instant() { return now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
    }
}
