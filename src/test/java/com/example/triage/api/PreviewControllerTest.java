package com.example.triage.api;

import com.example.triage.service.PreviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class PreviewControllerTest {
    @Autowired private WebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test void normalInput() throws Exception {
        mvc.perform(post("/api/previews").contentType(MediaType.APPLICATION_JSON).content("""
                {"symptom":"HTTP 500","log":"ERROR\\nstack","context":{"environment":"本番"}}
                """))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.masked_input.symptom").value("HTTP 500"))
                .andExpect(jsonPath("$.masked_input.context.environment").value("本番"))
                .andExpect(jsonPath("$.log_line_ids", contains("log:L1", "log:L2")))
                .andExpect(jsonPath("$.destination", containsString("送信なし")))
                .andExpect(jsonPath("$.preview_id", matchesPattern("[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.expires_at").isString());
    }

    @Test void missingSymptom() throws Exception {
        mvc.perform(post("/api/previews").contentType(MediaType.APPLICATION_JSON).content("{\"log\":\"ERROR\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.field_errors.symptom").value("必須項目です。"))
                .andExpect(jsonPath("$.request_id", matchesPattern("[0-9a-f-]{36}")))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test void storedContentMatchesHttpResponseAndDeleteUsesSession() throws Exception {
        var result = mvc.perform(post("/api/previews").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symptom\":\"障害\",\"log\":\"password=synthetic-secret\"}"))
                .andExpect(status().isOk()).andReturn();
        var json = new JsonMapper().readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        String id = json.get("preview_id").asString();
        var session = (org.springframework.mock.web.MockHttpSession) result.getRequest().getSession(false);
        var stored = context.getBean(PreviewService.class).get(id, session.getId()).response();
        org.junit.jupiter.api.Assertions.assertEquals(json.get("masked_input").get("log").asString(), stored.maskedInput().log());
        mvc.perform(delete("/api/previews/" + id)).andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("PREVIEW_UNAVAILABLE"));
        mvc.perform(delete("/api/previews/" + id).session(session)).andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(delete("/api/previews/" + id).session(session)).andExpect(status().isGone());
    }

    @Test void capacityErrorIsSanitized() throws Exception {
        var full = mock(PreviewService.class);
        when(full.create(any(), any())).thenThrow(new com.example.triage.runtime.PreviewCapacityException());
        MockMvcBuilders.standaloneSetup(new PreviewController(full)).setControllerAdvice(new ApiExceptionHandler()).build()
                .perform(post("/api/previews").contentType(MediaType.APPLICATION_JSON).content("{\"symptom\":\"障害\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("PREVIEW_CAPACITY"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test void inputLimitExceeded() throws Exception {
        String body = new JsonMapper().writeValueAsString(java.util.Map.of("symptom", "😀".repeat(2001), "log", "ERROR"));
        mvc.perform(post("/api/previews").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field_errors.symptom", containsString("2000")))
                .andExpect(content().string(not(containsString("😀"))));
    }

    @Test void masksSecretsBeforeReturningJson() throws Exception {
        mvc.perform(post("/api/previews").contentType(MediaType.APPLICATION_JSON).content("""
                {"symptom":"person@example.test","log":"password=synthetic-secret"}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.masked_input.log", containsString("[MASKED_")))
                .andExpect(content().string(not(containsString("synthetic-secret"))))
                .andExpect(content().string(not(containsString("person@example.test"))));
    }

    @Test void noLogWithStatus() throws Exception {
        mvc.perform(post("/api/previews").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symptom\":\"障害\",\"log_status\":\"未取得\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.masked_input.log").value(""))
                .andExpect(jsonPath("$.masked_input.log_status").value("未取得"))
                .andExpect(jsonPath("$.log_line_ids", empty()));
    }

    @Test void noLogWithoutStatus() throws Exception {
        mvc.perform(post("/api/previews").contentType(MediaType.APPLICATION_JSON).content("{\"symptom\":\"障害\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field_errors.log_status").exists());
    }

    @ParameterizedTest @ValueSource(strings = {"{invalid-secret", "", "null", "[]", "{\"symptom\":{\"secret\":true}}"})
    void invalidJson(String body) throws Exception {
        mvc.perform(post("/api/previews").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_JSON"))
                .andExpect(jsonPath("$.field_errors").isEmpty())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(not(containsString("secret"))))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test void wrongContentType() throws Exception {
        mvc.perform(post("/api/previews").contentType(MediaType.TEXT_PLAIN).content("secret"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(not(containsString("secret"))));
    }

    @Test void unexpectedExceptionIsSanitized() throws Exception {
        PreviewService failing = mock(PreviewService.class);
        when(failing.create(any(), any())).thenThrow(new IllegalStateException("password=internal-secret",
                new RuntimeException("C:/private/internal.java")));
        MockMvc failedMvc = MockMvcBuilders.standaloneSetup(new PreviewController(failing))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        failedMvc.perform(post("/api/previews").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symptom\":\"障害\",\"log\":\"ERROR\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.*", hasSize(4)))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("処理中にエラーが発生しました。"))
                .andExpect(jsonPath("$.request_id", matchesPattern("[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.field_errors").isEmpty())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(not(containsString("internal"))))
                .andExpect(content().string(not(containsString("Exception"))));
        verify(failing).create(any(), any());
    }

    @Test void unsupportedMethod() throws Exception {
        mvc.perform(get("/api/previews")).andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test void unsupportedResponseType() throws Exception {
        mvc.perform(post("/api/previews").contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_PLAIN)
                        .content("{\"symptom\":\"障害\",\"log\":\"ERROR\"}"))
                .andExpect(status().isNotAcceptable()).andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }
}
