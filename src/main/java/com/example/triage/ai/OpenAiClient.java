package com.example.triage.ai;

import com.example.triage.dto.MaskedIncidentInput;
import com.example.triage.runtime.AnalysisLimits;
import com.example.triage.validation.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class OpenAiClient implements AiClient, TokenCounter {
    private final OpenAiSettings settings;
    private final AnalysisLimits limits;
    private final OpenAiTransport transport;
    private final Supplier<String> apiKey;
    private final OpenAiRequestFactory requests;
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public OpenAiClient(OpenAiSettings settings, AnalysisLimits limits, OpenAiTransport transport, Supplier<String> apiKey) {
        settings.validate(); this.settings = settings; this.limits = limits;
        this.transport = transport; this.apiKey = apiKey; requests = new OpenAiRequestFactory(settings);
    }
    public String analyze(MaskedIncidentInput input) { throw new IllegalStateException("Reviewed references and call options are required"); }
    public AiResponse analyze(MaskedIncidentInput input, AiCallOptions options) { throw new IllegalStateException("Reviewed references are required"); }
    public long count(MaskedIncidentInput input) { throw new IllegalStateException("Reviewed references are required"); }
    public long count(MaskedIncidentInput input, SourceReferences sources) {
        // Deliberately conservative local admission: UTF-8 bytes of the FULL generation JSON.
        // Not an exact tokenizer. Exact API count below is mandatory; failure never falls back.
        return requests.generation(requests.countRequest(input, sources), limits.maxOutputTokens).getBytes(StandardCharsets.UTF_8).length;
    }
    public AiResponse analyze(MaskedIncidentInput input, SourceReferences sources, AiCallOptions options) {
        String key = apiKey.get();
        if (key == null || key.isBlank()) throw new AiFailure(AiFailure.Kind.PROVIDER_UNAVAILABLE);
        var counted = requests.countRequest(input, sources);
        String generation = requests.generation(counted, options.maxOutputTokens());
        long localBound = generation.getBytes(StandardCharsets.UTF_8).length;
        checkTokens(localBound);
        checkCost(localBound, options);
        long deadline = System.nanoTime() + Math.min(Duration.ofSeconds(50).toNanos(), options.timeout().toNanos());
        var count = post("/responses/input_tokens", requests.serialize(counted), key, deadline);
        long exact = nonnegative(count.get("input_tokens"));
        checkTokens(exact); checkCost(exact, options);
        var response = post("/responses", generation, key, deadline);
        if (!"completed".equals(response.path("status").asString()) || !response.path("error").isNull()
                && !response.path("error").isMissingNode()) throw invalid();
        var output = response.get("output");
        if (output == null || !output.isArray() || output.size() != 1) throw invalid();
        var message = output.get(0);
        var content = message.get("content");
        if (!"message".equals(message.path("type").asString()) || !"completed".equals(message.path("status").asString())
                || content == null || !content.isArray() || content.size() != 1
                || !"output_text".equals(content.get(0).path("type").asString())
                || !content.get(0).path("text").isString()) throw invalid();
        long usedInput = nonnegative(response.path("usage").get("input_tokens"));
        long usedOutput = nonnegative(response.path("usage").get("output_tokens"));
        long cached = nonnegative(response.path("usage").path("input_tokens_details").get("cached_tokens"));
        if (cached > usedInput) throw invalid();
        if (usedOutput >= options.maxOutputTokens()) throw invalid();
        return new AiResponse(content.get(0).get("text").asString(), usedInput, usedOutput, settings.actualCost(usedInput, cached, usedOutput));
    }
    private JsonNode post(String path, String body, String key, long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new AiFailure(AiFailure.Kind.TIMEOUT);
        var reply = transport.post(path, body, key, Duration.ofNanos(remaining));
        if (reply.status() == 429 || reply.status() >= 500) throw new AiFailure(AiFailure.Kind.PROVIDER_UNAVAILABLE);
        if (reply.status() == 400 || reply.status() == 422) throw invalid();
        if (reply.status() < 200 || reply.status() >= 300) throw new AiFailure(AiFailure.Kind.PROVIDER_UNAVAILABLE);
        try { return mapper.readTree(reply.body()); }
        catch (tools.jackson.core.JacksonException e) { throw invalid(); }
    }
    private long nonnegative(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0) throw invalid();
        return value.asLong();
    }
    private void checkTokens(long count) {
        if (count > limits.maxInputTokens) throw new InputValidationException(Map.of("preview_id", "入力上限を超えています。抜粋して再確認してください。"));
    }
    private void checkCost(long count, AiCallOptions options) {
        if (settings.cost(count, options.maxOutputTokens()).compareTo(options.maxCostUsd()) > 0)
            throw new CostLimitException();
    }
    public static final class CostLimitException extends RuntimeException {}
    private ContractViolationException invalid() { return new ContractViolationException(ContractViolationException.Code.INVALID_STRUCTURE); }
}
