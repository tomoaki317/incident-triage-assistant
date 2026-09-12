package com.example.triage.ai;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;

/** No SDK retries, redirects, tools or arbitrary destinations. */
public final class JdkOpenAiTransport implements OpenAiTransport {
    private final HttpClient http;
    public JdkOpenAiTransport() {
        if (!System.getProperty("jdk.httpclient.HttpClient.log", "").isBlank()
                || Boolean.getBoolean("jdk.internal.httpclient.debug"))
            throw new IllegalStateException("HTTP wire logging must be disabled for OpenAI");
        System.setProperty("jdk.httpclient.disableRetryConnect", "true");
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public Reply post(String path, String body, String key, Duration timeout) {
        if (!path.equals("/responses") && !path.equals("/responses/input_tokens")) throw new IllegalArgumentException("Unsupported path");
        if (timeout.isZero() || timeout.isNegative()) throw new AiFailure(AiFailure.Kind.TIMEOUT);
        var request = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1" + path))
                .timeout(timeout).header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            var response = pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            return new Reply(response.statusCode(), response.body());
        } catch (TimeoutException e) {
            pending.cancel(true); throw new AiFailure(AiFailure.Kind.TIMEOUT);
        } catch (InterruptedException e) {
            pending.cancel(true); Thread.currentThread().interrupt(); throw new AiFailure(AiFailure.Kind.TIMEOUT);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof HttpTimeoutException) throw new AiFailure(AiFailure.Kind.TIMEOUT);
            throw new AiFailure(AiFailure.Kind.PROVIDER_UNAVAILABLE);
        }
    }
}
