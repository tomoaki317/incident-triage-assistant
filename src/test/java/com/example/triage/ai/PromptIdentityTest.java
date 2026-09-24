package com.example.triage.ai;

import com.example.triage.dto.*;
import com.example.triage.validation.SourceReferences;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PromptIdentityTest {
    @Test void logsOnlyIdentityOfRetainedInstructionsOncePerFactory() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(OpenAiRequestFactory.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            var settings = new OpenAiSettings(); settings.setModel("synthetic-model");
            var factory = new OpenAiRequestFactory(settings);
            var input = new MaskedIncidentInput("synthetic-private-input", "", "未取得", null);
            var request = factory.countRequest(input, new SourceReferences(Set.of("symptom", "log_status"), Set.of()));
            String instructions = (String) request.get("instructions");
            try (var stream = getClass().getResourceAsStream("/prompts/triage-v1.txt")) {
                assertEquals(new String(stream.readAllBytes(), StandardCharsets.UTF_8), instructions);
            }
            var generated = new tools.jackson.databind.json.JsonMapper().readTree(factory.generation(request, 100));
            assertEquals(instructions, generated.get("instructions").asString());
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(instructions.getBytes(StandardCharsets.UTF_8)));
            assertTrue(digest.matches("[0-9a-f]{64}"));
            assertEquals(1, appender.list.size());
            var event = appender.list.getFirst();
            assertEquals("OpenAI prompt loaded version=triage-v1 sha256=" + digest, event.getFormattedMessage());
            assertEquals(ch.qos.logback.classic.Level.INFO, event.getLevel());
            assertNull(event.getThrowableProxy());
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
}
