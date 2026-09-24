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
    @ParameterizedTest @ValueSource(strings = {"missing", "unknown", "provided", "limits", "masked"})
    void dedicatedFieldsComeOnlyFromReviewedInput(String mode) throws Exception {
        String occurred = switch (mode) {
            case "provided" -> " 2026-09-24 12:00 JST\n";
            case "limits" -> "😀".repeat(100);
            case "masked" -> "a@b ".repeat(25);
            default -> "不明";
        };
        String destination = switch (mode) {
            case "provided" -> " 担当窓口\n";
            case "limits" -> "😀".repeat(200);
            case "masked" -> "c@d ".repeat(50);
            default -> "不明";
        };
        var context = mode.equals("missing") ? null : new IncidentContext(occurred,
                mode.equals("unknown") ? "不明" : "本番", "入力の影響",
                mode.equals("unknown") ? "不明" : "継続中", "変更の申告", "確認の申告", destination);
        var preview = previews.create(new IncidentInput("本番で12時に発生、担当は窓口、継続中",
                "2026-09-24 12:00 ERROR\nException\nDuplicate entry", null, context), session.getId());
        var snapshot = store.get(preview.previewId(), session.getId());
        var root = sortableResult();
        var e = (tools.jackson.databind.node.ObjectNode) root.get("escalation");
        for (String field : new String[]{"occurred_at", "environment", "ongoing_status", "destination"}) {
            e.put(field, "AIによる補完");
            assertEquals(!mode.equals("missing"), snapshot.sources().inputFieldIds().contains("context." + field));
        }
        var original = validator.validate(root.toString(), snapshot.sources());
        var actual = service(input -> root.toString()).analyze(preview.previewId(), session.getId()).result();
        var masked = snapshot.response().maskedInput().context();
        assertEquals(masked.occurredAt(), actual.escalation().occurredAt());
        assertEquals(masked.environment(), actual.escalation().environment());
        assertEquals(masked.ongoingStatus(), actual.escalation().ongoingStatus());
        assertEquals(masked.destination(), actual.escalation().destination());
        assertEquals(original.assessmentStatus(), actual.assessmentStatus());
        assertEquals(original.facts(), actual.facts());
        assertEquals(original.hypotheses(), actual.hypotheses());
        assertEquals(original.escalation().relatedFactIds(), actual.escalation().relatedFactIds());
        assertEquals(original.escalation().hypothesisIds(), actual.escalation().hypothesisIds());
        assertEquals(original.escalation().impact(), actual.escalation().impact());
        assertEquals(original.escalation().checksPerformed(), actual.escalation().checksPerformed());
        assertEquals(original.summary(), actual.summary());
        assertEquals(original.assessmentReason(), actual.assessmentReason());
        assertEquals(original.missingInformation(), actual.missingInformation());
        assertEquals(original.escalation().summary(), actual.escalation().summary());
        assertEquals(original.escalation().openQuestions(), actual.escalation().openQuestions());
        assertEquals(new java.util.HashSet<>(original.checks()), new java.util.HashSet<>(actual.checks()));
        for (String field : new String[]{"occurred_at", "environment", "ongoing_status", "destination"})
            assertEquals(!mode.equals("missing"), snapshot.sources().inputFieldIds().contains("context." + field));
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "null", "length", "type"})
    void invalidTransferredFieldIsRejectedBeforeComposition(String failure) throws Exception {
        var root = sortableResult();
        var escalation = (tools.jackson.databind.node.ObjectNode) root.get("escalation");
        switch (failure) {
            case "missing" -> escalation.remove("occurred_at");
            case "null" -> escalation.putNull("occurred_at");
            case "length" -> escalation.put("occurred_at", "x".repeat(1001));
            default -> escalation.put("occurred_at", 123);
        }
        var preview = preview("障害", "時刻\n例外\n重複");
        mvc(input -> root.toString()).perform(post("/api/analyses").session(session)
                .contentType(MediaType.APPLICATION_JSON).content(body(preview.previewId())))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.result").doesNotExist());
    }

    @Test void invalidComposedResultIsRejectedAndNotDisplayed() throws Exception {
        // Inject a corrupt internal snapshot: the AI response is valid, only composition exceeds the contract.
        var prepared = previews.preview(new IncidentInput("障害", "時刻\n例外\n重複", null, null));
        var masked = new MaskedIncidentInput(prepared.maskedInput().symptom(), prepared.maskedInput().log(),
                prepared.maskedInput().logStatus(), new IncidentContext("x".repeat(1001), "不明", "不明", "不明", "不明", "不明", "不明"));
        var sources = new SourceReferences(java.util.Set.of("symptom", "context.occurred_at"),
                java.util.Set.copyOf(prepared.logLineIds()));
        var preview = store.save(new PreviewResponse(masked, prepared.logLineIds(), prepared.destination(),
                prepared.purpose(), prepared.warnings()), sources, session.getId());
        var root = sortableResult();
        assertNotNull(validator.validate(root.toString(), sources));
        mvc(input -> root.toString()).perform(post("/api/analyses").session(session)
                .contentType(MediaType.APPLICATION_JSON).content(body(preview.previewId())))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.result").doesNotExist())
                .andExpect(header().string("Cache-Control", "no-store"));
        assertThrows(PreviewUnavailableException.class, () -> store.get(preview.previewId(), session.getId()));
    }

    @Test void checksAreStablySortedInApiWithoutChangingContentOrReferences() throws Exception {
        var mapper = new tools.jackson.databind.json.JsonMapper();
        var root = sortableResult();
        var original = root.deepCopy();
        var response = preview("HTTP 500", "ERROR\nException\nDuplicate entry");
        var returned = mvc(input -> root.toString()).perform(post("/api/analyses").session(session)
                .contentType(MediaType.APPLICATION_JSON).content(body(response.previewId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var actual = mapper.readTree(returned).get("result");
        var expected = original.deepCopy();
        var ordered = mapper.createArrayNode();
        for (int index : new int[]{1, 3, 0, 4, 2}) ordered.add(original.get("checks").get(index));
        expected.set("checks", ordered);
        assertEquals(expected, actual);
    }

    @ParameterizedTest @ValueSource(strings = {"priority", "reference", "state"})
    void invalidUnsortedResultsAreNotRescued(String failure) throws Exception {
        var root = sortableResult();
        switch (failure) {
            case "priority" -> ((tools.jackson.databind.node.ObjectNode) root.get("checks").get(0)).put("priority", "urgent");
            case "reference" -> ((tools.jackson.databind.node.ObjectNode) root.get("hypotheses").get(0))
                    .set("check_ids", new tools.jackson.databind.json.JsonMapper().createArrayNode().add("C99"));
            default -> root.put("assessment_status", "insufficient_information");
        }
        var response = preview("HTTP 500", "ERROR\nException\nDuplicate entry");
        mvc(input -> root.toString()).perform(post("/api/analyses").session(session)
                .contentType(MediaType.APPLICATION_JSON).content(body(response.previewId())))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.result").doesNotExist());
    }

    private tools.jackson.databind.node.ObjectNode sortableResult() throws Exception {
        var mapper = new tools.jackson.databind.json.JsonMapper();
        try (var stream = getClass().getResourceAsStream("/contracts/hypotheses-available.json")) {
            var root = (tools.jackson.databind.node.ObjectNode) mapper.readTree(stream);
            var checks = mapper.createArrayNode();
            String[] priorities = {"medium", "high", "low", "high", "medium"};
            for (int i = 0; i < priorities.length; i++) checks.add(mapper.createObjectNode()
                    .put("id", "C" + (i + 1)).put("action", "Synthetic check " + i)
                    .put("purpose", "Synthetic evidence " + i).put("priority", priorities[i]));
            root.set("checks", checks);
            return root;
        }
    }

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
