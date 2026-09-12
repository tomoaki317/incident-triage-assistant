package com.example.triage.ai;

import com.example.triage.dto.*;
import com.example.triage.masking.MaskingService;
import com.example.triage.runtime.*;
import com.example.triage.service.*;
import com.example.triage.validation.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class OpenAiClientTest {
    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"count,401,provider 4xx", "responses,429,provider 4xx", "count,503,provider 5xx", "responses,500,provider 5xx", "count,0,timeout", "responses,0,connection failure"})
    void diagnosticsContainOnlySafeMetadata(String api, int status, String classification) {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(OpenAiClient.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            var snapshot = snapshot("synthetic-private-incident", "password=synthetic-secret");
            OpenAiTransport transport = (path, body, key, timeout) -> {
                if (api.equals("responses") && path.endsWith("input_tokens"))
                    return new OpenAiTransport.Reply(200, "{\"input_tokens\":1000}");
                if (status == 0) throw new AiFailure(classification.equals("timeout") ? AiFailure.Kind.TIMEOUT : AiFailure.Kind.PROVIDER_UNAVAILABLE);
                return new OpenAiTransport.Reply(status, "private-provider-body");
            };
            var client = new OpenAiClient(settings, limits, transport, () -> "synthetic-test-key");
            assertThrows(AiFailure.class, () -> call(client, snapshot));
            assertEquals(1, appender.list.size());
            var event = appender.list.getFirst();
            assertEquals("OpenAI failure api=" + (api.equals("count") ? "token count" : "responses")
                    + " http_status=" + (status == 0 ? "unavailable" : status)
                    + " classification=" + classification + " model=" + settings.getModel(), event.getFormattedMessage());
            assertNull(event.getThrowableProxy());
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
    private final JsonMapper mapper = new JsonMapper();
    private final AnalysisLimits limits = new AnalysisLimits();
    private final OpenAiSettings settings = settings();
    private final PreviewStore store = new PreviewStore();
    private final PreviewService previews = new PreviewService(new InputValidator(), new MaskingService(), store);
    private final TriageResultValidator validator = new TriageResultValidator(new SchemaValidator(), new ReferenceValidator());
    private final AiCallOptions options = new AiCallOptions(Duration.ofSeconds(50), 8000, new BigDecimal("0.03"));
    private OpenAiSettings settings() {
        var s = new OpenAiSettings(); s.setModel("gpt-4.1-mini-2025-04-14");
        s.setInputUsdPerMillion(new BigDecimal("0.40")); s.setOutputUsdPerMillion(new BigDecimal("1.60"));
        s.setCachedInputUsdPerMillion(new BigDecimal("0.10")); return s;
    }
    private PreviewStore.Snapshot snapshot(String symptom, String log) {
        var response = previews.create(new IncidentInput(symptom, log, log == null ? "未取得" : null, null), "owner");
        return store.get(response.previewId(), "owner");
    }
    private String valid(PreviewStore.Snapshot s) { return new StubAiClient().analyze(s.response().maskedInput()); }
    private String envelope(String json) {
        return mapper.writeValueAsString(Map.of("status", "completed", "output", List.of(Map.of(
                "type", "message", "status", "completed", "content", List.of(Map.of("type", "output_text", "text", json)))),
                "usage", Map.of("input_tokens", 1000, "output_tokens", 100, "input_tokens_details", Map.of("cached_tokens", 200))));
    }
    private final class Fake implements OpenAiTransport {
        final List<String> paths = new ArrayList<>(); final List<String> bodies = new ArrayList<>();
        int status = 200; long tokens = 1000; String generated;
        boolean timeout;
        Fake(String json) { generated = envelope(json); }
        public Reply post(String path, String body, String key, Duration timeoutValue) {
            paths.add(path); bodies.add(body);
            assertEquals("synthetic-test-key", key);
            assertTrue(timeoutValue.compareTo(Duration.ofSeconds(50)) <= 0);
            if (timeout) throw new AiFailure(AiFailure.Kind.TIMEOUT);
            if (status != 200) return new Reply(status, "private-provider-error");
            return new Reply(200, path.endsWith("input_tokens") ? "{\"input_tokens\":" + tokens + "}" : generated);
        }
    }
    private OpenAiClient client(Fake f) { return new OpenAiClient(settings, limits, f, () -> "synthetic-test-key"); }
    private AiResponse call(OpenAiClient c, PreviewStore.Snapshot s) { return c.analyze(s.response().maskedInput(), s.sources(), options); }

    @Test void normalStructuredOutputIncludesOnlyReviewedContentAndAllRequestParts() {
        var s = snapshot("person@example.test", "password=synthetic-secret\nDuplicate entry");
        var f = new Fake(valid(s)); var c = client(f);
        var result = call(c, s); assertNotNull(validator.validate(result.json(), s.sources()));
        assertEquals(List.of("/responses/input_tokens", "/responses"), f.paths);
        var count = mapper.readTree(f.bodies.get(0)); var generation = mapper.readTree(f.bodies.get(1));
        for (String key : List.of("model", "instructions", "input", "text")) assertEquals(count.get(key), generation.get(key));
        assertEquals(8000, generation.get("max_output_tokens").asInt());
        assertFalse(generation.get("store").asBoolean());
        assertEquals("disabled", generation.get("truncation").asString());
        assertTrue(generation.at("/text/format/strict").asBoolean());
        assertFalse(f.bodies.get(1).contains("person@example.test"));
        assertFalse(f.bodies.get(1).contains("synthetic-secret"));
        assertFalse(f.bodies.get(1).contains("synthetic-test-key"));
        assertTrue(f.bodies.get(1).contains("log:L2"));
        assertTrue(f.bodies.get(1).contains("input_field_ids"));
        assertFalse(f.bodies.get(1).contains("allOf"));
        assertTrue(c.count(s.response().maskedInput(), s.sources()) > new StubTokenCounter().count(s.response().maskedInput()));
        assertEquals(new BigDecimal("0.00050"), result.actualCostUsd().stripTrailingZeros().setScale(5));
    }

    @ParameterizedTest @ValueSource(ints = {429, 500, 502, 503})
    void providerFailuresNeverRetryOrExposeBody(int status) {
        var s = snapshot("障害", null); var f = new Fake(valid(s)); f.status = status;
        var e = assertThrows(AiFailure.class, () -> call(client(f), s));
        assertEquals(AiFailure.Kind.PROVIDER_UNAVAILABLE, e.kind());
        assertEquals(1, f.paths.size()); assertNull(e.getCause()); assertFalse(e.getMessage().contains("private"));
    }
    @Test void timeoutMapsToExistingTimeoutFailure() {
        var s = snapshot("障害", null); var f = new Fake(valid(s)); f.timeout = true;
        assertEquals(AiFailure.Kind.TIMEOUT, assertThrows(AiFailure.class, () -> call(client(f), s)).kind());
        assertEquals(1, f.paths.size());
    }
    @ParameterizedTest @ValueSource(strings = {"incomplete", "failed", "cancelled", "queued"})
    void incompleteResponseRejected(String state) {
        var s = snapshot("障害", null); var f = new Fake(valid(s));
        f.generated = f.generated.replace("completed", state);
        assertThrows(ContractViolationException.class, () -> call(client(f), s));
    }
    @Test void refusalRejected() {
        var s = snapshot("障害", null); var f = new Fake(valid(s)); f.generated = f.generated.replace("output_text", "refusal");
        assertThrows(ContractViolationException.class, () -> call(client(f), s));
    }
    @Test void outputLimitReachedRejected() {
        var s = snapshot("障害", null); var f = new Fake(valid(s));
        f.generated = f.generated.replace("\"output_tokens\":100", "\"output_tokens\":8000");
        assertThrows(ContractViolationException.class, () -> call(client(f), s));
    }
    @ParameterizedTest @ValueSource(strings = {"json", "schema", "reference", "state"})
    void originalValidatorRejectsBadStructuredTextThroughAnalysisService(String failure) {
        var s = snapshot("障害", null);
        String json = switch (failure) {
            case "json" -> "```json\n{}\n```";
            case "schema" -> "{}";
            case "reference" -> valid(s).replace("\"symptom\"", "\"context.impact\"");
            default -> valid(s).replace("insufficient_information", "hypotheses_available");
        };
        var c = client(new Fake(json));
        try (var control = new ControlResource(limits)) {
            var service = new AnalysisService(store, c, validator, limits, control.value, c);
            assertThrows(ContractViolationException.class, () -> service.analyze(s.response().previewId(), "owner"));
        }
    }
    @Test void localInputLimitRejectsBeforeAnyNetworkCall() {
        var s = snapshot("障害", "界".repeat(20000)); var f = new Fake(valid(s));
        assertThrows(InputValidationException.class, () -> call(client(f), s)); assertTrue(f.paths.isEmpty());
    }
    @Test void exactInputLimitRejectsBeforeGeneration() {
        var s = snapshot("障害", null); var f = new Fake(valid(s)); f.tokens = 32001;
        assertThrows(InputValidationException.class, () -> call(client(f), s));
        assertEquals(List.of("/responses/input_tokens"), f.paths);
    }
    @Test void costLimitRejectsBeforeAnyNetworkCall() {
        var s = snapshot("障害", null); var f = new Fake(valid(s)); settings.setOutputUsdPerMillion(new BigDecimal("100"));
        assertThrows(OpenAiClient.CostLimitException.class, () -> call(client(f), s)); assertTrue(f.paths.isEmpty());
    }
    @Test void missingApiKeyRejectsBeforeNetwork() {
        var s = snapshot("障害", null); var f = new Fake(valid(s));
        assertThrows(AiFailure.class, () -> call(new OpenAiClient(settings, limits, f, () -> null), s));
        assertTrue(f.paths.isEmpty());
    }
    @Test void countFailureNeverFallsBackToEstimate() {
        var s = snapshot("障害", null); var f = new Fake(valid(s)); f.tokens = -1;
        assertThrows(ContractViolationException.class, () -> call(client(f), s)); assertEquals(1, f.paths.size());
    }
    @Test void unsupportedSchemaRejectionUsesValidationFailure() {
        var s = snapshot("障害", null); var f = new Fake(valid(s)); f.status = 400;
        assertThrows(ContractViolationException.class, () -> call(client(f), s));
    }
    @Test void defaultModeUsesOnlyStub() {
        runner().run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertInstanceOf(StubAiClient.class, ctx.getBean(AiClient.class));
            assertInstanceOf(StubTokenCounter.class, ctx.getBean(TokenCounter.class));
        });
    }
    @Test void openAiModeSelectsRealAdapterWithoutNetwork() {
        runner().withPropertyValues("triage.ai.mode=openai").run(ctx -> {
            assertNull(ctx.getStartupFailure()); assertInstanceOf(OpenAiClient.class, ctx.getBean(AiClient.class));
            assertSame(ctx.getBean(AiClient.class), ctx.getBean(TokenCounter.class));
        });
    }
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(OpenAiConfiguration.class, StubAiClient.class, StubTokenCounter.class)
                .withBean(OpenAiSettings.class, () -> settings).withBean(AnalysisLimits.class, () -> limits);
    }
    private static class ControlResource implements AutoCloseable {
        final AnalysisControl value;
        ControlResource(AnalysisLimits limits) { value = new AnalysisControl(limits); }
        public void close() { value.close(); }
    }
}
