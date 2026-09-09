package com.example.triage.validation;

import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public final class ReferenceValidator {
    /** Must be called after structural validation. */
    public void validate(JsonNode root, SourceReferences sources) {
        Set<String> facts = uniqueIds(root.get("facts"));
        Set<String> hypotheses = uniqueIds(root.get("hypotheses"));
        Set<String> checks = uniqueIds(root.get("checks"));

        for (JsonNode fact : root.get("facts")) {
            Set<String> allowed = "log".equals(fact.get("source_type").asString())
                    ? sources.logLineIds() : sources.inputFieldIds();
            require(allowed.contains(fact.get("source_ref").asString()));
        }
        for (JsonNode hypothesis : root.get("hypotheses")) {
            requireReferences(hypothesis.get("evidence_fact_ids"), facts);
            requireReferences(hypothesis.get("check_ids"), checks);
        }
        JsonNode escalation = root.get("escalation");
        requireReferences(escalation.get("related_fact_ids"), facts);
        requireReferences(escalation.get("hypothesis_ids"), hypotheses);
    }

    private Set<String> uniqueIds(JsonNode elements) {
        Set<String> ids = new HashSet<>();
        for (JsonNode element : elements) {
            require(ids.add(element.get("id").asString()));
        }
        return ids;
    }

    private void requireReferences(JsonNode references, Set<String> ids) {
        for (JsonNode reference : references) {
            require(ids.contains(reference.asString()));
        }
    }

    private void require(boolean condition) {
        if (!condition) {
            throw new ContractViolationException(ContractViolationException.Code.INVALID_REFERENCE);
        }
    }
}
