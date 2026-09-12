package com.example.triage.ai;

import com.example.triage.runtime.AnalysisLimits;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Configuration
@ConditionalOnProperty(name = "triage.ai.mode", havingValue = "openai")
public class OpenAiConfiguration {
    @Bean public OpenAiClient openAiClient(OpenAiSettings settings, AnalysisLimits limits) {
        return new OpenAiClient(settings, limits, new JdkOpenAiTransport(), () -> System.getenv("OPENAI_API_KEY"));
    }
}
