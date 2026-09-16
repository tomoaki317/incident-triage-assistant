package com.example.triage.validation;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.resource.SchemaLoader;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public final class SchemaValidator {
    private static final org.slf4j.Logger DIAGNOSTICS = org.slf4j.LoggerFactory.getLogger(SchemaValidator.class);
    private static final java.util.Set<String> KEYWORDS = java.util.Set.of(
            "type", "required", "additionalProperties", "const", "enum", "pattern",
            "minLength", "maxLength", "minItems", "maxItems", "allOf", "if", "then", "else");
    private final Schema schema;

    public SchemaValidator() {
        // The default loader permits bundled classpath schemas, not remote fetching.
        var registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemaLoader(SchemaLoader.getDefault()));
        schema = registry.getSchema(SchemaLocation.of("classpath:schema/triage-result.schema.json"));
    }

    public void validate(JsonNode node) {
        if (node == null) {
            DIAGNOSTICS.warn("Analysis validation classification=INVALID_STRUCTURE item=schema.type");
            throw new ContractViolationException(ContractViolationException.Code.INVALID_STRUCTURE);
        }
        var errors = schema.validate(node);
        if (!errors.isEmpty()) {
            // Never log Error, message, arguments or instance paths (unknown keys may contain secrets).
            errors.stream().map(error -> KEYWORDS.contains(error.getKeyword()) ? error.getKeyword() : "other")
                    .distinct().sorted().forEach(keyword -> DIAGNOSTICS.warn(
                            "Analysis validation classification=INVALID_STRUCTURE item=schema.{}", keyword));
            throw new ContractViolationException(ContractViolationException.Code.INVALID_STRUCTURE);
        }
    }
}
