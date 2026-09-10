package com.example.triage.ai;

import com.example.triage.dto.MaskedIncidentInput;
import org.springframework.stereotype.Component;

/** Only for Stub flow testing: code points of serialized input, NOT model tokens. */
@Component
public class StubTokenCounter implements TokenCounter {
    public long count(MaskedIncidentInput input) {
        String json = new tools.jackson.databind.json.JsonMapper().writeValueAsString(input);
        return json.codePointCount(0, json.length());
    }
}
