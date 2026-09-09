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
    private final Schema schema;

    public SchemaValidator() {
        // The default loader permits bundled classpath schemas, not remote fetching.
        var registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemaLoader(SchemaLoader.getDefault()));
        schema = registry.getSchema(SchemaLocation.of("classpath:schema/triage-result.schema.json"));
    }

    public void validate(JsonNode node) {
        if (node == null || !schema.validate(node).isEmpty()) {
            throw new ContractViolationException(ContractViolationException.Code.INVALID_STRUCTURE);
        }
    }
}
