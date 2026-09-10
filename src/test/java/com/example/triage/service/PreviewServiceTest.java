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
    private final PreviewService service = new PreviewService(new InputValidator(), new MaskingService());

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
