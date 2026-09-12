package com.example.triage.ai;

import com.example.triage.dto.MaskedIncidentInput;
import com.example.triage.validation.SourceReferences;
import java.util.*;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.json.JsonMapper;

/** One payload builder for local preflight, server token counting and generation. */
public final class OpenAiRequestFactory {
    private final JsonMapper mapper = new JsonMapper();
    private final Object schema;
    private final String instructions;
    private final OpenAiSettings settings;
    public OpenAiRequestFactory(OpenAiSettings settings) {
        this.settings = settings;
        try (var stream = getClass().getResourceAsStream("/schema/triage-result.schema.json");
             var prompt = getClass().getResourceAsStream("/prompts/triage-v1.txt")) {
            if (stream == null || prompt == null) throw new IllegalStateException();
            var raw = mapper.readValue(stream, Map.class);
            schema = supportedSchema(raw);
            instructions = new String(prompt.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException("Unable to load fixed OpenAI resources"); }
    }
    // OpenAI does not support conditional allOf. Only the transport schema is adapted;
    // the original schema and all state/length/reference checks still run server-side.
    private Object supportedSchema(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach((key, child) -> {
                if (!key.equals("$schema") && !key.equals("allOf")) result.put(key.toString(), supportedSchema(child));
            });
            if (result.containsKey("const") || result.containsKey("enum")) result.putIfAbsent("type", "string");
            if ("array".equals(result.get("type"))) result.putIfAbsent("items", Map.of("type", "string"));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(this::supportedSchema).toList();
        return value;
    }
    public Map<String, Object> countRequest(MaskedIncidentInput input, SourceReferences sources) {
        var data = new LinkedHashMap<String, Object>();
        data.put("masked_input", input);
        data.put("input_field_ids", new TreeSet<>(sources.inputFieldIds()));
        data.put("log_line_ids", sources.logLineIds().stream().sorted(Comparator.comparingInt(id -> Integer.parseInt(id.substring(5)))).toList());
        return Map.of("model", settings.getModel(), "instructions", instructions,
                "input", List.of(Map.of("role", "user", "content", mapper.writeValueAsString(data))),
                "text", Map.of("format", Map.of("type", "json_schema", "name", "triage_result", "strict", true, "schema", schema)));
    }
    public String serialize(Object request) { return mapper.writeValueAsString(request); }
    public String generation(Map<String, Object> counted, int maxOutput) {
        var request = new LinkedHashMap<>(counted);
        request.put("max_output_tokens", maxOutput); request.put("store", false);
        request.put("stream", false); request.put("truncation", "disabled");
        return serialize(request);
    }
}
