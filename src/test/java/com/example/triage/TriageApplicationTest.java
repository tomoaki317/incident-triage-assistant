package com.example.triage;

import com.example.triage.validation.TriageResultValidator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TriageApplicationTest {
    @Autowired
    private TriageResultValidator validator;

    @Test
    void loadsValidationComponentsWithoutAiOrServer() {
        assertNotNull(validator);
    }
}
