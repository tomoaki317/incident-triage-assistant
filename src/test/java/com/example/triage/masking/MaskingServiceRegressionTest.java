package com.example.triage.masking;

import com.example.triage.dto.IncidentInput;
import com.example.triage.service.PreviewService;
import com.example.triage.validation.InputValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class MaskingServiceRegressionTest {
    private final PreviewService previews = new PreviewService(new InputValidator(), new MaskingService());

    private String mask(String log) {
        return previews.preview(new IncidentInput("HTTP 500", log, null, null)).maskedInput().log();
    }

    @ParameterizedTest
    @ValueSource(strings = {"password=\"abc\"DEF-secret\"", "password=\"abc\\\"DEF-secret\"",
            "password='abc\\'DEF-secret'", "password='abc'DEF-secret'"})
    void masksEntirePasswordWithInternalQuotes(String input) {
        String masked = mask(input + " status=500");
        assertFalse(masked.contains("abc"));
        assertFalse(masked.contains("DEF-secret"));
        assertTrue(masked.endsWith(" status=500"));
        assertTrue(masked.contains("[MASKED_1]"));
    }

    @ParameterizedTest @ValueSource(strings = {"Authorization", "Cookie"})
    void preservesOtherJsonFields(String header) {
        String value = header.equals("Cookie") ? "session=token; other=value" : "Bearer token";
        String masked = mask("{\"" + header + "\":\"" + value + "\",\"status\":500,\"message\":\"DB timeout\"}");
        var json = new JsonMapper().readTree(masked);
        assertEquals("[MASKED_1]", json.get(header).asString());
        assertEquals(500, json.get("status").asInt());
        assertEquals("DB timeout", json.get("message").asString());
        assertFalse(masked.contains("token"));
    }

    @ParameterizedTest @ValueSource(strings = {"Authorization", "Cookie"})
    void preservesSeparateHeadersAndLineEndings(String header) {
        String masked = mask(header + ": secret\r\nstatus: 500\nmessage: DB timeout\r");
        assertEquals(header + ": [MASKED_1]\r\nstatus: 500\nmessage: DB timeout\r", masked);
    }

    @Test void masksWholeSemicolonConnectionString() {
        assertEquals("connection_string=[MASKED_1]", mask(
                "connection_string=Server=db;User ID=synthetic-user;Password=synthetic-secret"));
    }

    @Test void connectionStringKeepsFollowingJsonFields() {
        String masked = mask("{\"connection_string\":\"Server=db;User ID=synthetic-user;Password=synthetic-secret\",\"status\":500}");
        var json = new JsonMapper().readTree(masked);
        assertEquals("[MASKED_1]", json.get("connection_string").asString());
        assertEquals(500, json.get("status").asInt());
    }

    @Test void connectionStringKeepsFollowingDelimitedField() {
        assertEquals("connection_string=[MASKED_1], status=500", mask(
                "connection_string=Server=db;User ID=synthetic-user;Password=synthetic-secret, status=500"));
    }

    @Test void repeatedSecretsKeepAliasesAndLineIds() {
        String secret = "Server=db;User ID=synthetic-user;Password=synthetic-secret";
        var preview = previews.preview(new IncidentInput("connection_string=" + secret,
                "connection_string=\"" + secret + "\"\r\npassword=\"abc\\\"DEF-secret\"\npassword=\"abc\\\"DEF-secret\"\r",
                null, null));
        assertEquals("connection_string=[MASKED_1]", preview.maskedInput().symptom());
        assertEquals("connection_string=\"[MASKED_1]\"\r\npassword=\"[MASKED_2]\"\npassword=\"[MASKED_2]\"\r",
                preview.maskedInput().log());
        assertEquals(java.util.List.of("log:L1", "log:L2", "log:L3", "log:L4"), preview.logLineIds());
    }

    @Test void unterminatedQuoteDoesNotLeakOrConsumeNextLine() {
        assertEquals("password=[MASKED_1]\nstatus=500", mask("password=\"abc\\\"DEF-secret\nstatus=500"));
    }
}
