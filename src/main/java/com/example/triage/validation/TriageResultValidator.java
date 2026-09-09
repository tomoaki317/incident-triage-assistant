package com.example.triage.validation;

import com.example.triage.dto.TriageResult;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public final class TriageResultValidator {
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private final SchemaValidator schemaValidator;
    private final ReferenceValidator referenceValidator;

    public TriageResultValidator(SchemaValidator schemaValidator, ReferenceValidator referenceValidator) {
        this.schemaValidator = schemaValidator;
        this.referenceValidator = referenceValidator;
    }

    public TriageResult validate(String json, SourceReferences sources) {
        if (json == null || json.isBlank()) {
            throw new ContractViolationException(ContractViolationException.Code.INVALID_JSON);
        }
        final JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JacksonException ex) {
            // Do not retain the parser exception: it may include confidential input.
            throw new ContractViolationException(ContractViolationException.Code.INVALID_JSON);
        }
        schemaValidator.validate(root);
        referenceValidator.validate(root, sources);
        try {
            return mapper.treeToValue(root, TriageResult.class);
        } catch (JacksonException ex) {
            throw new ContractViolationException(ContractViolationException.Code.INVALID_STRUCTURE);
        }
    }
}
