package com.example.triage.api;

import com.example.triage.ai.*;
import com.example.triage.dto.*;
import com.example.triage.runtime.*;
import com.example.triage.service.*;
import com.example.triage.masking.MaskingService;
import com.example.triage.validation.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AnalysisControlTest {
    private final AnalysisLimits limits = new AnalysisLimits();
    private final AnalysisControl control = new AnalysisControl(limits);
    private final PreviewStore store = new PreviewStore();
    private final MockHttpSession session = new MockHttpSession();
    private final TriageResultValidator validator = new TriageResultValidator(new SchemaValidator(), new ReferenceValidator());
    @AfterEach void close() { control.close(); }
    private String preview() {
        return new PreviewService(new InputValidator(), new MaskingService(), store)
                .create(new IncidentInput("障害", null, "未取得", null), session.getId()).previewId();
    }
    private AnalysisService service(AiClient client) { return new AnalysisService(store, client, validator, limits, control, input -> 10); }
    @Test void issuesUuidAndRejectsReplayEvenAfterPreviewDeletion() {
        var service = service(new StubAiClient()); var id = preview();
        var response = service.analyze(id, session.getId());
        assertEquals(4, UUID.fromString(response.executionId()).version());
        assertThrows(AnalysisControl.Duplicate.class, () -> service.analyze(id, session.getId(), response.executionId()));
        assertThrows(AnalysisControl.Duplicate.class, () -> service.analyze(preview(), "other", response.executionId()));
    }
    @Test void suppliedExecutionIdIsRetained() {
        String execution = UUID.randomUUID().toString();
        assertEquals(execution, service(new StubAiClient()).analyze(preview(), session.getId(), execution).executionId());
    }
    @ParameterizedTest @ValueSource(strings = {"1", "", "00000000-0000-0000-0000-000000000000"})
    void invalidExecutionIdRejected(String id) {
        assertThrows(InputValidationException.class, () -> service(new StubAiClient()).analyze(preview(), session.getId(), id));
    }
    @Test void twoConcurrentRequestsAndThirdRejectedWithoutQueue() throws Exception {
        var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
        var service = service(input -> {
            entered.countDown();
            try { if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException(); }
            catch (InterruptedException e) { throw new IllegalStateException(); }
            return new StubAiClient().analyze(input);
        });
        var a = preview(); var b = preview(); var c = preview();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.analyze(a, session.getId()));
            var second = executor.submit(() -> service.analyze(b, session.getId()));
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertThrows(AnalysisControl.Capacity.class, () -> service.analyze(c, session.getId()));
                assertNotNull(store.get(c, session.getId()));
            } finally { release.countDown(); }
            assertNotNull(first.get()); assertNotNull(second.get());
        }
    }
    @Test void timeoutReturnsWithoutWaitingForUncooperativeClient() throws Exception {
        limits.timeout = Duration.ofMillis(100);
        var release = new CountDownLatch(1); var ended = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var service = service(input -> {
            calls.incrementAndGet();
            while (release.getCount() != 0) { try { release.await(); } catch (InterruptedException ignored) {} }
            ended.countDown(); return new StubAiClient().analyze(input);
        });
        try {
            var ex = assertThrows(AiFailure.class, () -> service.analyze(preview(), session.getId()));
            assertEquals(AiFailure.Kind.TIMEOUT, ex.kind());
            assertEquals(1, calls.get());
        } finally { release.countDown(); assertTrue(ended.await(2, TimeUnit.SECONDS)); }
    }
    @ParameterizedTest @ValueSource(ints = {429, 500, 503})
    void providerFailureMapsTo503WithNoRetry(int status) throws Exception {
        var calls = new AtomicInteger();
        var service = service(input -> { calls.incrementAndGet(); throw AiFailure.providerStatus(status); });
        var mvc = MockMvcBuilders.standaloneSetup(new AnalysisController(service)).setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(post("/api/analyses").session(session).contentType("application/json")
                .content("{\"preview_id\":\"" + preview() + "\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.trace").doesNotExist());
        assertEquals(1, calls.get());
    }
    @Test void timeoutMapsTo504() throws Exception {
        limits.timeout = Duration.ofMillis(50);
        var mvc = MockMvcBuilders.standaloneSetup(new AnalysisController(service(input -> {
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            return "invalid";
        }))).setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(post("/api/analyses").session(session).contentType("application/json")
                .content("{\"preview_id\":\"" + preview() + "\"}"))
                .andExpect(status().isGatewayTimeout()).andExpect(header().string("Cache-Control", "no-store"));
    }
    @Test void limitsAndZeroRetriesReachClientAndUsageCanBeReturned() {
        var client = new AiClient() {
            public String analyze(MaskedIncidentInput input) { throw new AssertionError(); }
            public AiResponse analyze(MaskedIncidentInput input, AiCallOptions options) {
                assertEquals(0, options.maxRetries()); assertEquals(8000, options.maxOutputTokens());
                assertTrue(options.timeout().compareTo(Duration.ofSeconds(50)) <= 0);
                assertEquals(new java.math.BigDecimal("0.03"), options.maxCostUsd());
                return new AiResponse(new StubAiClient().analyze(input), 10L, 100L);
            }
        };
        assertNotNull(service(client).analyze(preview(), session.getId()));
    }
    @Test void inputLimitStopsClient() {
        limits.maxInputTokens = 9;
        assertThrows(InputValidationException.class, () -> service(input -> { fail("must not call"); return ""; }).analyze(preview(), session.getId()));
    }
    @Test void executionRecordsAreBounded() {
        limits.maxExecutionRecords = 1;
        var service = service(new StubAiClient()); service.analyze(preview(), session.getId());
        assertThrows(AnalysisControl.Capacity.class, () -> service.analyze(preview(), session.getId()));
    }

    @Test void capacityMapsTo429() throws Exception {
        limits.maxExecutionRecords = 1;
        var service = service(new StubAiClient()); service.analyze(preview(), session.getId());
        MockMvcBuilders.standaloneSetup(new AnalysisController(service)).setControllerAdvice(new ApiExceptionHandler()).build()
                .perform(post("/api/analyses").session(session).contentType("application/json")
                        .content("{\"preview_id\":\"" + preview() + "\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("ANALYSIS_CAPACITY"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }
}
