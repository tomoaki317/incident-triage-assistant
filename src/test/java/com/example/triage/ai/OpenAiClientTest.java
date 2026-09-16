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

    @ParameterizedTest @ValueSource(strings = {"未取得", "取得できない", "該当ログなし"})
    void combinedLogStatusReachesBothRequestsExactlyAsPreviewed(String status) {
        var p = previews.create(new IncidentInput("別の時刻のログ。関連は未確認。",
                "password=synthetic-secret\r\nINFO synthetic", status, null), "owner");
        var s = store.get(p.previewId(), "owner");
        var f = new Fake(valid(s)); var c = client(f);
        try (var control = new ControlResource(limits)) {
            new AnalysisService(store, c, validator, limits, control.value, c).analyze(p.previewId(), "owner");
        }
        assertEquals(2, f.bodies.size());
        for (String body : f.bodies) {
            var data = mapper.readTree(mapper.readTree(body).at("/input/0/content").asString());
            assertEquals(mapper.valueToTree(p.maskedInput()), data.get("masked_input"));
            assertEquals(status, data.at("/masked_input/log_status").asString());
            assertTrue(data.get("input_field_ids").toString().contains("log_status"));
            assertEquals(mapper.valueToTree(p.logLineIds()), data.get("log_line_ids"));
            assertFalse(body.contains("synthetic-secret"));
        }
        assertTrue(p.warnings().stream().anyMatch(w -> w.startsWith("ログ本文とログ取得状況")));
    }

    @ParameterizedTest @ValueSource(strings = {"A", "B", "C"})
    void fixedQualityCasesPassHttpContractWithFakeTransport(String id) throws Exception {
        tools.jackson.databind.JsonNode definition = null;
        for (var entry : mapper.readTree(evaluationResource("/evaluation/cases.json")))
            if (entry.get("id").asString().equals(id)) definition = entry;
        assertNotNull(definition);
        var input = mapper.readValue(evaluationResource(definition.get("input_resource").asString()), IncidentInput.class);
        String response = evaluationResource(definition.get("example_response_resource").asString());
        var session = new org.springframework.mock.web.MockHttpSession();
        var p = previews.create(input, session.getId());
        var f = new Fake(response); var c = client(f);
        try (var control = new ControlResource(limits)) {
            var service = new AnalysisService(store, c, validator, limits, control.value, c);
            var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                    .standaloneSetup(new com.example.triage.api.AnalysisController(service))
                    .setControllerAdvice(new com.example.triage.api.ApiExceptionHandler()).build();
            var actual = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .post("/api/analyses").session(session).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of("preview_id", p.previewId()))))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"))
                    .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            var result = mapper.readTree(actual).get("result");
            assertEquals(mapper.readTree(response), result);
            assertEquals(definition.get("assessment_status"), result.get("assessment_status"));
            if (!id.equals("A")) {
                assertTrue(result.get("hypotheses").isEmpty());
                assertTrue(result.at("/escalation/hypothesis_ids").isEmpty());
            }
        }
        for (String body : f.bodies) {
            var data = mapper.readTree(mapper.readTree(body).at("/input/0/content").asString());
            assertEquals(mapper.valueToTree(p.maskedInput()), data.get("masked_input"));
            assertEquals(mapper.valueToTree(p.logLineIds()), data.get("log_line_ids"));
            for (var evidence : definition.get("log_evidence").properties()) {
                var line = data.get("log_lines").get(Integer.parseInt(evidence.getKey().substring(5)) - 1);
                assertEquals(evidence.getKey(), line.get("source_ref").asString());
                assertTrue(line.get("text").asString().contains(evidence.getValue().asString()));
            }
            if (id.equals("A")) assertFalse(data.get("input_field_ids").toString().contains("log_status"));
        }
        assertEquals(2, f.paths.size());
        assertFalse(definition.get("human_checks").isEmpty());
    }

    private String evaluationResource(String path) throws Exception {
        try (var stream = getClass().getResourceAsStream(path)) {
            assertNotNull(stream);
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    // Fixed synthetic responses test transport/contracts and evaluation criteria, not model quality.
    @ParameterizedTest @ValueSource(strings = {"out-of-scope", "hypotheses-available", "insufficient-information"})
    void manualCasesThroughFakeOpenAiAndAnalysisService(String fixture) throws Exception {
        var input = mapper.readValue(contract(fixture + "-input"), IncidentInput.class);
        var p = previews.create(input, "owner");
        var f = new Fake(contract(fixture)); var c = client(f);
        try (var control = new ControlResource(limits)) {
            var result = new AnalysisService(store, c, validator, limits, control.value, c)
                    .analyze(p.previewId(), "owner").result();
            assertEquals(mapper.readTree(contract(fixture)), mapper.valueToTree(result));
            var generation = mapper.readTree(f.bodies.get(1));
            var data = mapper.readTree(generation.at("/input/0/content").asString());
            String instructions = generation.get("instructions").asString();
            if (fixture.equals("hypotheses-available")) {
                assertTrue(duplicateFactSupported(result, data));
                assertTrue(instructions.contains("参照先のtextまたは入力項目の内容がstatement全体を裏付ける"));
                assertTrue(instructions.contains("複数行にまたがる事実は行ごとに分け"));
            } else {
                assertTrue(data.get("log_lines").isEmpty());
                assertTrue(result.hypotheses().isEmpty());
                assertTrue(result.escalation().hypothesisIds().isEmpty());
                assertEquals("不明", result.escalation().destination());
                if (fixture.equals("insufficient-information")) {
                    assertTrue(evidenceFirst(result));
                    assertTrue(instructions.contains("checksは証拠の取得を優先する"));
                    assertTrue(instructions.contains("原因候補をchecksやmissing_informationへ言い換えて混入させない"));
                } else {
                    assertTrue(instructions.contains("out_of_scopeでも全必須プロパティとescalationオブジェクトを出力する"));
                    assertTrue(instructions.contains("空文字やnullでなく「不明」"));
                }
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"\n", "\r\n", "\r"})
    void explicitLogMappingPreservesBlankTrailingAndDoubleDigitLines(String newline) {
        String log = "header" + newline + newline + "Duplicate entry 'SYNTHETIC'" + newline
                + String.join(newline, Collections.nCopies(8, "stack frame")) + newline;
        var s = snapshot("商品登録で500", log);
        var factory = new OpenAiRequestFactory(settings);
        var request = mapper.valueToTree(factory.countRequest(s.response().maskedInput(), s.sources()));
        var data = mapper.readTree(request.at("/input/0/content").asString());
        var lines = data.get("log_lines");
        String[] original = s.response().maskedInput().log().split("\\r\\n|\\r|\\n", -1);
        assertEquals(original.length, lines.size());
        for (int i = 0; i < original.length; i++) {
            assertEquals(s.response().logLineIds().get(i), lines.get(i).get("source_ref").asString());
            assertEquals(original[i], lines.get(i).get("text").asString());
        }
        assertEquals("", lines.get(1).get("text").asString());
        assertEquals("", lines.get(lines.size() - 1).get("text").asString());
        assertEquals("log:L10", lines.get(9).get("source_ref").asString());
    }

    @Test void evaluationRejectsExistingButUnsupportedLogReference() throws Exception {
        var input = mapper.readValue(contract("hypotheses-available-input"), IncidentInput.class);
        var s = snapshot(input.symptom(), input.log());
        String wrong = contract("hypotheses-available").replace("log:L3", "log:L1");
        // Existence validation alone passes. Content evaluation must reject the timestamp-only line.
        var result = validator.validate(wrong, s.sources());
        var request = mapper.valueToTree(new OpenAiRequestFactory(settings)
                .countRequest(s.response().maskedInput(), s.sources()));
        assertFalse(duplicateFactSupported(result, mapper.readTree(request.at("/input/0/content").asString())));
    }

    @Test void evaluationRejectsUnsupportedTechnologyChecksForBareHttp500() throws Exception {
        var s = snapshot("商品登録時にHTTP 500が発生した", null);
        String wrong = contract("insufficient-information")
                .replace("発生時刻付近のアプリケーションログを確認する", "DB接続プールの設定を確認する");
        assertFalse(evidenceFirst(validator.validate(wrong, s.sources())));
    }

    private boolean duplicateFactSupported(TriageResult result, tools.jackson.databind.JsonNode data) {
        var fact = result.facts().stream().filter(f -> f.id().equals("F2")).findFirst().orElseThrow();
        if (fact.sourceType() != SourceType.LOG || !fact.statement().contains("重複キー")) return false;
        for (var line : data.get("log_lines"))
            if (line.get("source_ref").asString().equals(fact.sourceRef()))
                return line.get("text").asString().contains("Duplicate entry");
        return false;
    }

    private boolean evidenceFirst(TriageResult result) {
        if (!result.hypotheses().isEmpty() || result.checks().isEmpty()) return false;
        var first = result.checks().getFirst();
        if (first.priority() != Priority.HIGH || !first.action().contains("ログ")) return false;
        String proposals = mapper.writeValueAsString(List.of(result.checks(), result.missingInformation()));
        return !proposals.matches("(?s).*(DB接続プール|HikariCP|認証方式|Spring Security).*");
    }

    private String contract(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream("/contracts/" + name + ".json")) {
            assertNotNull(stream);
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

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
