package com.example.triage.ai;

import com.example.triage.dto.IncidentInput;
import com.example.triage.masking.MaskingService;
import com.example.triage.service.PreviewService;
import com.example.triage.validation.*;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class QualityRegressionTest {
    private static final JsonMapper MAPPER = new JsonMapper();
    private final TriageResultValidator validator = new TriageResultValidator(new SchemaValidator(), new ReferenceValidator());

    static Stream<JsonNode> cases() throws Exception {
        return java.util.stream.StreamSupport.stream(read("/evaluation/quality-regressions.json").spliterator(), false);
    }

    @ParameterizedTest(name = "synthetic quality case {index}") @MethodSource("cases")
    void syntheticQualityFailuresRemainDistinctFromContractFailures(JsonNode example) throws Exception {
        var input = MAPPER.treeToValue(read(example.get("input").asString()), IncidentInput.class);
        var previews = new PreviewService(new InputValidator(), new MaskingService());
        var preview = previews.create(input, "quality-test");
        var sources = previews.get(preview.previewId(), "quality-test").sources();
        ObjectNode good = (ObjectNode) read(example.get("base").asString());
        String id = example.get("id").asString();
        String pointer = example.get("pointer").asString();
        if (example.has("source_ref")) {
            int last = pointer.lastIndexOf('/');
            ((ObjectNode) good.at(pointer.substring(0, last))).put("source_ref", example.get("source_ref").asString());
        }
        if (id.equals("missing_timezone")) {
            // Remove every mention so another field cannot hide the regression.
            good = (ObjectNode) MAPPER.readTree(good.toString().replace("タイムゾーン", "時刻"));
        }
        if (id.equals("missing_support_guidance")) {
            good.putArray("checks");
            ((ObjectNode) good.get("escalation")).putArray("open_questions");
        }
        replace(good, pointer, example.get("good").asString());
        ObjectNode bad = good.deepCopy();
        replace(bad, pointer, example.get("bad").asString());
        // These are all legal JSON contracts. Production does not pretend to prove semantics.
        assertDoesNotThrow(() -> validator.validate(bad.toString(), sources));
        final ObjectNode corrected = good;
        assertDoesNotThrow(() -> validator.validate(corrected.toString(), sources));
        assertFalse(fixedCasePasses(id, bad, pointer, input));
        assertTrue(fixedCasePasses(id, good, pointer, input));

        var settings = new OpenAiSettings(); settings.setModel("synthetic-model");
        var factory = new OpenAiRequestFactory(settings);
        var count = factory.countRequest(preview.maskedInput(), sources);
        var generation = MAPPER.readTree(factory.generation(count, 8000));
        assertEquals(MAPPER.valueToTree(count.get("instructions")), generation.get("instructions"));
        assertTrue(generation.get("instructions").asString().contains(example.get("prompt_guard").asString()));
        var data = MAPPER.readTree(generation.at("/input/0/content").asString());
        assertEquals(MAPPER.valueToTree(preview.maskedInput()), data.get("masked_input"));
    }

    /** Narrow checks for these authored examples only, NOT a general semantic validator. */
    private boolean fixedCasePasses(String id, JsonNode result, String pointer, IncidentInput input) {
        String text = result.at(pointer).asString();
        return switch (id) {
            case "mixed_log_lines" -> {
                String ref = result.at("/facts/1/source_ref").asString();
                String line = input.log().split("\\r\\n|\\r|\\n", -1)[Integer.parseInt(ref.substring(5)) - 1];
                yield Stream.of("2026-09-09 14:32:15", "DataIntegrityViolationException")
                        .filter(text::contains).allMatch(line::contains);
            }
            case "status_scope" -> text.contains(input.logStatus()) && !text.contains("環境");
            case "unconfirmed_correlation" -> text.contains("対応は未確認") && !text.contains("同時刻");
            case "unconfirmed_escalation" -> text.contains("対応は未確認") && !text.contains("確認された");
            case "invented_progress" -> !text.contains("調査が進んでいない") && !text.contains("確認された");
            case "unsupported_database_check" -> !text.contains("DB接続") && !text.contains("制約違反");
            case "missing_timezone" -> Stream.of(result.get("checks"), result.get("missing_information"))
                    .anyMatch(n -> n.toString().contains("タイムゾーン"));
            case "missing_support_guidance" -> text.contains("サポート窓口") && text.contains("確認");
            case "invented_key_meaning" -> !text.contains("商品コード");
            default -> throw new AssertionError("Unknown synthetic quality case");
        };
    }

    @ParameterizedTest @CsvSource({
            "insufficient_information,hypotheses", "out_of_scope,hypotheses",
            "insufficient_information,hypothesis_ids", "out_of_scope,hypothesis_ids"
    })
    void deterministicStateAndReferenceRulesStillRejectCandidates(String state, String field) throws Exception {
        ObjectNode root = (ObjectNode) read("/contracts/hypotheses-available.json");
        root.put("assessment_status", state);
        if (field.equals("hypothesis_ids")) root.putArray("hypotheses");
        var sources = new SourceReferences(java.util.Set.of("symptom"), java.util.Set.of("log:L1", "log:L2", "log:L3"));
        var error = assertThrows(ContractViolationException.class, () -> validator.validate(root.toString(), sources));
        assertEquals(field.equals("hypotheses") ? ContractViolationException.Code.INVALID_STRUCTURE
                : ContractViolationException.Code.INVALID_REFERENCE, error.code());
        assertNull(error.getCause());
    }

    private static void replace(ObjectNode root, String pointer, String value) {
        int last = pointer.lastIndexOf('/');
        ((ObjectNode) (last == 0 ? root : root.at(pointer.substring(0, last))))
                .put(pointer.substring(last + 1), value);
    }
    private static JsonNode read(String path) throws Exception {
        try (var stream = QualityRegressionTest.class.getResourceAsStream(path)) {
            assertNotNull(stream);
            return MAPPER.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
