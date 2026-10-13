package dev.switchyard;

import java.util.concurrent.atomic.*;

final class Metrics {
    final LongAdder requests = new LongAdder(), retries = new LongAdder(), rateLimited = new LongAdder(),
            overloaded = new LongAdder(), receivedBytes = new LongAdder(), sentBytes = new LongAdder();
    final AtomicInteger inFlight = new AtomicInteger(), queued = new AtomicInteger();
    final LongAdder[] statuses = new LongAdder[6];
    private final double[] bounds = {.005, .01, .025, .05, .1, .25, .5, 1, 2, 5, 10, 30};
    private final LongAdder[] buckets = new LongAdder[bounds.length];
    private final LongAdder latencyCount = new LongAdder();
    private final DoubleAdder latencySum = new DoubleAdder();
    Metrics() {
        for (int i = 0; i < statuses.length; i++) statuses[i] = new LongAdder();
        for (int i = 0; i < buckets.length; i++) buckets[i] = new LongAdder();
    }
    void completed(int status, long started) {
        statuses[Math.min(5, Math.max(0, status / 100))].increment();
        double seconds = (System.nanoTime() - started) / 1e9;
        latencySum.add(seconds); latencyCount.increment();
        for (int i = 0; i < bounds.length; i++) if (seconds <= bounds[i]) buckets[i].increment();
    }
    String prometheus(BackendPool pool) {
        StringBuilder out = new StringBuilder();
        sample(out, "switchyard_requests_total", requests.sum());
        sample(out, "switchyard_retries_total", retries.sum());
        sample(out, "switchyard_rate_limited_total", rateLimited.sum());
        sample(out, "switchyard_overloaded_total", overloaded.sum());
        sample(out, "switchyard_in_flight", inFlight.get());
        sample(out, "switchyard_queued", queued.get());
        sample(out, "switchyard_request_bytes_total", receivedBytes.sum());
        sample(out, "switchyard_response_bytes_total", sentBytes.sum());
        for (int i = 1; i < statuses.length; i++) sample(out, "switchyard_responses_total{class=\"" + i + "xx\"}", statuses[i].sum());
        out.append("# TYPE switchyard_request_duration_seconds histogram\n");
        for (int i = 0; i < bounds.length; i++) sample(out, "switchyard_request_duration_seconds_bucket{le=\"" + bounds[i] + "\"}", buckets[i].sum());
        sample(out, "switchyard_request_duration_seconds_bucket{le=\"+Inf\"}", latencyCount.sum());
        sample(out, "switchyard_request_duration_seconds_count", latencyCount.sum());
        out.append("switchyard_request_duration_seconds_sum ").append(latencySum.sum()).append('\n');
        for (Backend backend : pool.backends()) {
            String label = "{backend=\"" + backend.id + "\"}";
            sample(out, "switchyard_backend_healthy" + label, backend.healthy() ? 1 : 0);
            sample(out, "switchyard_backend_active" + label, backend.active());
            sample(out, "switchyard_backend_attempts_total" + label, backend.attempts.sum());
            sample(out, "switchyard_backend_failures_total" + label, backend.failures.sum());
            sample(out, "switchyard_backend_circuit_state" + label, backend.circuit().ordinal());
        }
        return out.toString();
    }
    private static void sample(StringBuilder out, String name, long value) { out.append(name).append(' ').append(value).append('\n'); }
}
