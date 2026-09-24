package com.example.triage.service;

import com.example.triage.dto.*;
import com.example.triage.masking.MaskingService;
import com.example.triage.validation.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class PreviewServiceTest {
    @ParameterizedTest @ValueSource(strings = {"all", "empty", "partial", "unknown"})
    void uiContextValuesKeepPresenceAndNormalizeOnlyMissingFields(String mode) {
        String occurred = mode.equals("all") ? "2026-09-24 10:00 JST" : mode.equals("unknown") ? "不明" : "";
        String environment = mode.equals("empty") ? "" : mode.equals("unknown") ? "不明" : "検証";
        String ongoing = mode.equals("all") ? "解消済み" : mode.equals("unknown") ? "不明" : "";
        String destination = mode.equals("empty") ? "" : mode.equals("unknown") ? "不明" : "運用窓口";
        var mapper = new JsonMapper();
        var json = mapper.createObjectNode().put("symptom", "本番で10時に発生、継続中")
                .put("log", "").put("log_status", "未取得");
        json.set("context", mapper.createObjectNode().put("occurred_at", occurred).put("environment", environment)
                .put("ongoing_status", ongoing).put("destination", destination));
        var preview = service.create(mapper.treeToValue(json, IncidentInput.class), "ui-test");
        var sources = service.get(preview.previewId(), "ui-test").sources();
        var c = preview.maskedInput().context();
        String[] ids = {"occurred_at", "environment", "ongoing_status", "destination"};
        String[] raw = {occurred, environment, ongoing, destination};
        String[] masked = {c.occurredAt(), c.environment(), c.ongoingStatus(), c.destination()};
        for (int i = 0; i < ids.length; i++) {
            assertEquals(raw[i].isEmpty() ? "不明" : raw[i], masked[i]);
            assertEquals(!raw[i].isEmpty(), sources.inputFieldIds().contains("context." + ids[i]));
        }
    }

    private final PreviewService service = new PreviewService(new InputValidator(), new MaskingService());

    @ParameterizedTest @ValueSource(strings = {"未取得", "取得できない", "該当ログなし"})
    void logAndStatusArePreservedWithReviewWarning(String status) {
        var result = service.preview(new IncidentInput("別の時刻のログを提示。該当時刻との関係は未確認。", "INFO synthetic", status, null));
        assertEquals("INFO synthetic", result.maskedInput().log());
        assertEquals(status, result.maskedInput().logStatus());
        assertEquals(1, result.warnings().stream().filter(w -> w.startsWith("ログ本文とログ取得状況")).count());
    }

    @ParameterizedTest @ValueSource(strings = {"未取得", "取得できない", "該当ログなし"})
    void blankLogAndStatusDoNotProduceCombinationWarning(String status) {
        var result = service.preview(new IncidentInput("障害", " \r\n　", status, null));
        assertEquals("", result.maskedInput().log());
        assertEquals(status, result.maskedInput().logStatus());
        assertTrue(result.warnings().stream().noneMatch(w -> w.startsWith("ログ本文とログ取得状況")));
    }

    @Test void logWithoutStatusRemainsUnspecifiedWithoutCombinationWarning() {
        var result = service.preview(new IncidentInput("障害", "INFO synthetic", null, null));
        assertEquals("不明", result.maskedInput().logStatus());
        assertTrue(result.warnings().stream().noneMatch(w -> w.startsWith("ログ本文とログ取得状況")));
    }

    @Test void normalInput() {
        var raw = new IncidentInput("HTTP 500", "ERROR\r\nstack\n", null, null);
        var result = service.preview(raw);
        assertEquals(raw.log(), result.maskedInput().log());
        assertEquals(java.util.List.of("log:L1", "log:L2", "log:L3"), result.logLineIds());
        assertEquals("不明", result.maskedInput().context().impact());
        assertTrue(result.destination().contains("送信なし"));
    }

    @ParameterizedTest @ValueSource(strings = {"未取得", "取得できない", "該当ログなし"})
    void noLogWithStatus(String status) {
        var result = service.preview(new IncidentInput("障害", null, status, null));
        assertEquals("", result.maskedInput().log());
        assertEquals(status, result.maskedInput().logStatus());
        assertTrue(result.logLineIds().isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "\t\r\n", "\u3000", "\u00a0"})
    void blankLogRequiresStatus(String log) {
        var error = assertThrows(InputValidationException.class,
                () -> service.preview(new IncidentInput("障害", log, null, null)));
        assertTrue(error.fieldErrors().containsKey("log_status"));
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "\u3000"})
    void blankSymptomRejected(String symptom) {
        assertThrows(InputValidationException.class,
                () -> service.preview(new IncidentInput(symptom, "log", null, null)));
    }

    @Test void nullInputAndSymptomRejected() {
        assertThrows(InputValidationException.class, () -> service.preview(null));
        assertThrows(InputValidationException.class, () -> service.preview(new IncidentInput(null, "log", null, null)));
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
    void allCodePointBoundaries(int field) {
        int[] limits = {2000, 20000, 100, 1000, 1000, 2000, 200};
        String[] ids = {"symptom", "log", "context.occurred_at", "context.impact",
                "context.recent_changes", "context.checks_performed", "context.destination"};
        var values = new String[]{"障害", "log", null, null, null, null, null};
        values[field] = "😀".repeat(limits[field]);
        assertDoesNotThrow(() -> service.preview(input(values)));
        values[field] += "😀";
        var error = assertThrows(InputValidationException.class, () -> service.preview(input(values)));
        assertTrue(error.fieldErrors().containsKey(ids[field]));
        assertFalse(error.getMessage().contains("😀"));
    }

    private IncidentInput input(String[] v) {
        return new IncidentInput(v[0], v[1], null,
                new IncidentContext(v[2], null, v[3], null, v[4], v[5], v[6]));
    }

    @Test void rejectsUndefinedChoices() {
        assertThrows(InputValidationException.class, () -> service.preview(new IncidentInput("障害", "log", "invalid", null)));
        assertThrows(InputValidationException.class, () -> service.preview(new IncidentInput("障害", "log", null,
                new IncidentContext(null, "invalid", null, "invalid", null, null, null))));
    }

    @ParameterizedTest @ValueSource(strings = {
            "api_key=synthetic-secret", "API-Key: synthetic-secret", "\"apiKey\": \"synthetic-secret\"",
            "Authorization: Bearer synthetic-secret", "Cookie: session=synthetic-secret; other=value",
            "2026-09-10 DEBUG Authorization: Bearer synthetic-secret", "headers={Cookie: session=synthetic-secret}",
            "password='synthetic-secret'", "connection_string=\"synthetic-secret\"",
            "jdbc:mysql://synthetic-secret/db?user=test&password=test",
            "postgresql://user:synthetic-secret@host/db", "synthetic-secret@example.test"})
    void masksSpecifiedSecrets(String log) {
        var result = service.preview(new IncidentInput("障害", log, null, null));
        assertFalse(result.maskedInput().log().contains("synthetic-secret"));
        assertTrue(result.maskedInput().log().contains("[MASKED_"));
    }

    @Test void leavesOtherValuesAlone() {
        String log = "山田太郎 東京都 03-1234-5678 customerId=C001 internal.example HTTP 500 Duplicate entry 'ABC001'";
        assertEquals(log, service.preview(new IncidentInput("障害", log, null, null)).maskedInput().log());
    }

    @Test void multipleSecretsAndNewlines() {
        String log = "api_key=alpha\r\nAuthorization: Bearer beta\nCookie: token=gamma\rpassword=delta\n"
                + "jdbc:mysql://host/db?password=epsilon\nuser@example.test";
        String masked = service.preview(new IncidentInput("障害", log, null, null)).maskedInput().log();
        for (String secret : new String[]{"alpha", "beta", "gamma", "delta", "epsilon", "user@example.test"})
            assertFalse(masked.contains(secret));
        assertEquals(log.replaceAll("[^\\r\\n]", ""), masked.replaceAll("[^\\r\\n]", ""));
    }

    @Test void masksAllTextAndPreservesRawInput() {
        String email = "person@example.test";
        var raw = new IncidentInput(email, email + "\n" + email, null,
                new IncidentContext(email, "本番", email, "継続中", email, email, email));
        var result = service.preview(raw);
        String alias = result.maskedInput().symptom();
        assertEquals(alias + "\n" + alias, result.maskedInput().log());
        assertEquals(alias, result.maskedInput().context().occurredAt());
        assertEquals(alias, result.maskedInput().context().impact());
        assertEquals(alias, result.maskedInput().context().recentChanges());
        assertEquals(alias, result.maskedInput().context().checksPerformed());
        assertEquals(alias, result.maskedInput().context().destination());
        assertEquals(email, raw.symptom());
        assertFalse(new JsonMapper().writeValueAsString(result).contains(email));
        assertFalse(raw.toString().contains(email));
        assertFalse(raw.context().toString().contains(email));
        assertFalse(result.toString().contains(email));
    }

    @Test void aliasesAreRequestLocal() {
        var first = service.preview(new IncidentInput("a@example.test", "log", null, null));
        var second = service.preview(new IncidentInput("b@example.test", "log", null, null));
        assertEquals("[MASKED_1]", first.maskedInput().symptom());
        assertEquals("[MASKED_1]", second.maskedInput().symptom());
    }

    @Test void snakeCaseInputDeserializes() {
        var input = new JsonMapper().readValue("""
                {"symptom":"障害","log_status":"未取得","context":{"occurred_at":"不明","ongoing_status":"継続中"}}
                """, IncidentInput.class);
        assertEquals("未取得", service.preview(input).maskedInput().logStatus());
        assertEquals("継続中", input.context().ongoingStatus());
    }
}
