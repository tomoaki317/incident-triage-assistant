package com.example.triage.runtime;

import java.time.Duration;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "triage.analysis")
public class AnalysisLimits {
    public Duration timeout = Duration.ofSeconds(60);
    public Duration clientTimeout = Duration.ofSeconds(50);
    public int maxInputTokens = 32000;
    public int maxOutputTokens = 8000;
    public BigDecimal maxCostUsd = new BigDecimal("0.03");
    public int maxExecutionRecords = 1000;
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration v) { if (v.isNegative() || v.isZero() || v.compareTo(Duration.ofSeconds(60)) > 0) throw new IllegalArgumentException(); timeout = v; }
    public Duration getClientTimeout() { return clientTimeout; }
    public void setClientTimeout(Duration v) { if (v.isNegative() || v.isZero()) throw new IllegalArgumentException(); clientTimeout = v; }
    public int getMaxInputTokens() { return maxInputTokens; }
    public void setMaxInputTokens(int v) { if (v < 1) throw new IllegalArgumentException(); maxInputTokens = v; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int v) { if (v < 1) throw new IllegalArgumentException(); maxOutputTokens = v; }
    public BigDecimal getMaxCostUsd() { return maxCostUsd; }
    public void setMaxCostUsd(BigDecimal v) { if (v.signum() <= 0) throw new IllegalArgumentException(); maxCostUsd = v; }
    public int getMaxExecutionRecords() { return maxExecutionRecords; }
    public void setMaxExecutionRecords(int v) { if (v < 1) throw new IllegalArgumentException(); maxExecutionRecords = v; }
}
