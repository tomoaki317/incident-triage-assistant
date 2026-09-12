package com.example.triage.ai;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "triage.openai")
public class OpenAiSettings {
    private String model;
    private BigDecimal inputUsdPerMillion;
    private BigDecimal outputUsdPerMillion;
    private BigDecimal cachedInputUsdPerMillion;
    public BigDecimal getCachedInputUsdPerMillion() { return cachedInputUsdPerMillion; }
    public void setCachedInputUsdPerMillion(BigDecimal v) { cachedInputUsdPerMillion = v; }
    public String getModel() { return model; }
    public void setModel(String v) { model = v; }
    public BigDecimal getInputUsdPerMillion() { return inputUsdPerMillion; }
    public void setInputUsdPerMillion(BigDecimal v) { inputUsdPerMillion = v; }
    public BigDecimal getOutputUsdPerMillion() { return outputUsdPerMillion; }
    public void setOutputUsdPerMillion(BigDecimal v) { outputUsdPerMillion = v; }
    public void validate() {
        if (model == null || model.isBlank() || inputUsdPerMillion == null || outputUsdPerMillion == null
                || inputUsdPerMillion.signum() <= 0 || outputUsdPerMillion.signum() <= 0
                || cachedInputUsdPerMillion == null || cachedInputUsdPerMillion.signum() < 0
                || cachedInputUsdPerMillion.compareTo(inputUsdPerMillion) > 0)
            throw new IllegalStateException("OpenAI model and positive prices must be configured");
    }
    public BigDecimal cost(long input, long output) {
        return inputUsdPerMillion.multiply(BigDecimal.valueOf(input))
                .add(outputUsdPerMillion.multiply(BigDecimal.valueOf(output))).movePointLeft(6);
    }
    public BigDecimal actualCost(long input, long cached, long output) {
        return cost(input - cached, output).add(cachedInputUsdPerMillion.multiply(BigDecimal.valueOf(cached)).movePointLeft(6));
    }
}
