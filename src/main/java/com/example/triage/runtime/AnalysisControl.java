package com.example.triage.runtime;

import com.example.triage.ai.AiFailure;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongFunction;
import org.springframework.stereotype.Component;

@Component
public class AnalysisControl {
    public static final class Duplicate extends RuntimeException {}
    public static final class Capacity extends RuntimeException {}
    private record Execution(String owner, String preview, LocalDate day) {}
    private final Map<String, Execution> executions = new HashMap<>();
    private final AnalysisLimits limits;
    private int active;
    // Admission is bounded before submission; no queued work, even if cancellation is ignored.
    private final ExecutorService workers = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
    public AnalysisControl(AnalysisLimits limits) { this.limits = limits; }

    public <T> T execute(String id, String owner, String preview, LongFunction<T> operation) {
        long deadline = System.nanoTime() + limits.timeout.toNanos();
        synchronized (this) {
            var today = LocalDate.now(ZoneId.of("Asia/Tokyo"));
            executions.values().removeIf(e -> e.day.isBefore(today));
            if (executions.containsKey(id)) throw new Duplicate();
            if (active >= 2 || executions.size() >= limits.maxExecutionRecords) throw new Capacity();
            executions.put(id, new Execution(owner, preview, today));
            active++;
        }
        FutureTask<T> task = new FutureTask<>(() -> operation.apply(deadline));
        try {
            workers.execute(() -> {
                try { task.run(); } finally { synchronized (this) { active--; } }
            });
        } catch (RejectedExecutionException e) {
            synchronized (this) { active--; executions.remove(id); }
            throw new Capacity();
        }
        try {
            return task.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            throw new AiFailure(AiFailure.Kind.TIMEOUT);
        } catch (InterruptedException e) {
            task.cancel(true); Thread.currentThread().interrupt();
            throw new AiFailure(AiFailure.Kind.TIMEOUT);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Analysis failed");
        }
    }

    @jakarta.annotation.PreDestroy
    public void close() { workers.shutdownNow(); }
}
