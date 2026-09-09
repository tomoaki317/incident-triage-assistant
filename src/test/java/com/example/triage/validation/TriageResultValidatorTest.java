package com.example.triage.validation;

import com.example.triage.dto.AssessmentStatus;
import com.example.triage.dto.Priority;
import com.example.triage.dto.SourceType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.*;

class TriageResultValidatorTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private final TriageResultValidator validator = new TriageResultValidator(
            new SchemaValidator(), new ReferenceValidator());

    @ParameterizedTest
    @CsvSource({
            "hypotheses-available,HYPOTHESES_AVAILABLE",
            "insufficient-information,INSUFFICIENT_INFORMATION",
            "out-of-scope,OUT_OF_SCOPE"
    })
    void acceptsAllThreeStatesAndRoundTrips(String fixture, AssessmentStatus expected) throws IOException {
        String json = resource(fixture);
        SourceReferences sources = sources(fixture);
        var result = validator.validate(json, sources);
        assertEquals(expected, result.assessmentStatus());
        String serialized = MAPPER.writeValueAsString(result);
        assertEquals(MAPPER.readTree(json), MAPPER.readTree(serialized));
        assertEquals(result, validator.validate(serialized, sources));
        assertTrue(result.references().isEmpty());
        if (expected == AssessmentStatus.HYPOTHESES_AVAILABLE) {
            assertEquals(SourceType.LOG, result.facts().get(1).sourceType());
            assertEquals(Priority.HIGH, result.checks().getFirst().priority());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidMutations")
    void rejectsInvalidContracts(String name, Consumer<ObjectNode> mutation,
                                ContractViolationException.Code code) throws IOException {
        ObjectNode root = candidate();
        mutation.accept(root);
        var error = assertThrows(ContractViolationException.class,
                () -> validator.validate(root.toString(), sources("hypotheses-available")));
        assertEquals(code, error.code());
        assertEquals(code.name(), error.getMessage());
        assertNull(error.getCause());
    }

    static Stream<Arguments> invalidMutations() {
        var structure = ContractViolationException.Code.INVALID_STRUCTURE;
        var reference = ContractViolationException.Code.INVALID_REFERENCE;
        return Stream.of(
                bad("missing required", r -> r.remove("summary"), structure),
                bad("nested required", r -> object(r, "/escalation").remove("impact"), structure),
                bad("null", r -> r.putNull("summary"), structure),
                bad("unknown property", r -> r.put("unexpected", "secret"), structure),
                bad("nested unknown property", r -> object(r, "/facts/0").put("extra", "secret"), structure),
                bad("wrong type", r -> r.put("summary", 123), structure),
                bad("empty text", r -> r.put("summary", ""), structure),
                bad("long text", r -> r.put("summary", "a".repeat(1001)), structure),
                bad("long reference", r -> object(r, "/facts/0").put("source_ref", "a".repeat(101)), structure),
                bad("wrong version", r -> r.put("schema_version", "2.0"), structure),
                bad("unknown state", r -> r.put("assessment_status", "confirmed"), structure),
                bad("unknown priority", r -> object(r, "/checks/0").put("priority", "urgent"), structure),
                bad("unknown source type", r -> object(r, "/facts/0").put("source_type", "manual"), structure),
                bad("invalid id", r -> object(r, "/facts/0").put("id", "F0"), structure),
                bad("empty references forbidden", r -> array(r, "/references").addObject(), structure),
                bad("insufficient with hypothesis", r -> r.put("assessment_status", "insufficient_information"), structure),
                bad("out of scope with hypothesis", r -> r.put("assessment_status", "out_of_scope"), structure),
                bad("available without hypothesis", r -> array(r, "/hypotheses").removeAll(), structure),
                bad("no evidence", r -> array(r, "/hypotheses/0/evidence_fact_ids").removeAll(), structure),
                bad("no checks", r -> array(r, "/hypotheses/0/check_ids").removeAll(), structure),
                bad("missing fact", r -> array(r, "/hypotheses/0/evidence_fact_ids").add("F999"), reference),
                bad("missing check", r -> array(r, "/hypotheses/0/check_ids").add("C999"), reference),
                bad("missing escalation fact", r -> array(r, "/escalation/related_fact_ids").add("F999"), reference),
                bad("missing escalation hypothesis", r -> array(r, "/escalation/hypothesis_ids").add("H999"), reference),
                bad("missing log line", r -> object(r, "/facts/1").put("source_ref", "log:L999"), reference),
                bad("unprovided field", r -> object(r, "/facts/0").put("source_ref", "context.impact"), reference),
                bad("log pointing to input", r -> object(r, "/facts/1").put("source_ref", "symptom"), reference),
                bad("input pointing to log", r -> object(r, "/facts/0").put("source_ref", "log:L3"), reference),
                bad("duplicate fact id", r -> array(r, "/facts").add(r.at("/facts/0").deepCopy()), reference),
                bad("duplicate hypothesis id", r -> array(r, "/hypotheses").add(r.at("/hypotheses/0").deepCopy()), reference),
                bad("duplicate check id", r -> array(r, "/checks").add(r.at("/checks/0").deepCopy()), reference)
        );
    }

    @ParameterizedTest
    @CsvSource({
            "/facts,20", "/hypotheses,3", "/checks,5", "/missing_information,10",
            "/hypotheses/0/evidence_fact_ids,20", "/hypotheses/0/check_ids,5",
            "/hypotheses/0/unverified_assumptions,5", "/escalation/related_fact_ids,20",
            "/escalation/hypothesis_ids,3", "/escalation/checks_performed,10",
            "/escalation/open_questions,10"
    })
    void rejectsEveryArrayAboveItsLimit(String pointer, int limit) throws IOException {
        ObjectNode root = candidate();
        ArrayNode values = array(root, pointer);
        JsonNode item = values.isEmpty() ? MAPPER.valueToTree("確認事項") : values.get(0).deepCopy();
        while (values.size() <= limit) {
            values.add(item.deepCopy());
        }
        var error = assertThrows(ContractViolationException.class,
                () -> validator.validate(root.toString(), sources("hypotheses-available")));
        assertEquals(ContractViolationException.Code.INVALID_STRUCTURE, error.code());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "{", "null", "[]", "{} {}", "```json\n{}\n```"})
    void rejectsNonContractInput(String json) {
        assertThrows(ContractViolationException.class,
                () -> validator.validate(json, new SourceReferences(Set.of(), Set.of())));
    }

    @Test
    void rejectsDuplicateKeysAndTrailingJsonWithoutLeakingBody() throws IOException {
        String valid = resource("hypotheses-available");
        String duplicate = valid.replace("\"summary\":", "\"summary\": \"CONFIDENTIAL\", \"summary\":");
        for (String json : new String[] {duplicate, valid + " {}", "{\"secret\":CONFIDENTIAL}"}) {
            var error = assertThrows(ContractViolationException.class,
                    () -> validator.validate(json, sources("hypotheses-available")));
            assertEquals(ContractViolationException.Code.INVALID_JSON, error.code());
            assertFalse(error.toString().contains("CONFIDENTIAL"));
            assertNull(error.getCause());
        }
    }

    @Test
    void countsSupplementaryCharactersAsCodePoints() throws IOException {
        ObjectNode root = candidate();
        root.put("summary", "😀".repeat(1000));
        assertDoesNotThrow(() -> validator.validate(root.toString(), sources("hypotheses-available")));
        root.put("summary", "😀".repeat(1001));
        assertThrows(ContractViolationException.class,
                () -> validator.validate(root.toString(), sources("hypotheses-available")));
    }

    @Test
    void cannotUseSourcesOutsideCallerProvidedInput() throws IOException {
        assertThrows(ContractViolationException.class,
                () -> validator.validate(resource("hypotheses-available"), new SourceReferences(Set.of(), Set.of())));
        assertThrows(IllegalArgumentException.class,
                () -> new SourceReferences(Set.of("invented"), Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new SourceReferences(Set.of(), Set.of("symptom")));
    }

    @Test
    void rejectsJavaNull() {
        assertThrows(ContractViolationException.class,
                () -> validator.validate(null, new SourceReferences(Set.of(), Set.of())));
    }

    private static Arguments bad(String name, Consumer<ObjectNode> mutation, ContractViolationException.Code code) {
        return Arguments.of(name, mutation, code);
    }

    private static ObjectNode object(JsonNode root, String pointer) {
        return (ObjectNode) root.at(pointer);
    }

    private static ArrayNode array(JsonNode root, String pointer) {
        return (ArrayNode) root.at(pointer);
    }

    private static ObjectNode candidate() throws IOException {
        return (ObjectNode) MAPPER.readTree(resource("hypotheses-available"));
    }

    private static SourceReferences sources(String fixture) throws IOException {
        JsonNode input = MAPPER.readTree(resource(fixture + "-input"));
        Set<String> fields = new HashSet<>();
        Set<String> lines = new HashSet<>();
        if (input.has("symptom")) fields.add("symptom");
        if (input.has("log_status")) fields.add("log_status");
        if (input.has("log")) {
            int count = input.get("log").asString().split("\n", -1).length;
            for (int i = 1; i <= count; i++) lines.add("log:L" + i);
        }
        return new SourceReferences(fields, lines);
    }

    private static String resource(String name) throws IOException {
        try (var stream = TriageResultValidatorTest.class.getResourceAsStream("/contracts/" + name + ".json")) {
            assertNotNull(stream);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
