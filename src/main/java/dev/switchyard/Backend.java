package dev.switchyard;

import java.net.URI;
import java.util.concurrent.atomic.LongAdder;

/** All state transitions are synchronized; network I/O happens outside this monitor. */
final class Backend {
    enum Circuit { CLOSED, OPEN, HALF_OPEN }
    record Ticket(Backend backend, long generation, boolean probe) {}
    final String id;
    final URI origin;
    final LongAdder attempts = new LongAdder(), failures = new LongAdder();
    private boolean healthy;
    private int healthBad, healthGood, consecutiveFailures, active;
    private long generation, openedAt;
    private Circuit circuit = Circuit.CLOSED;

    Backend(Config.BackendSpec spec) { id = spec.id(); origin = spec.origin(); }
    synchronized boolean healthy() { return healthy; }
    synchronized int active() { return active; }
    synchronized Circuit circuit() { return circuit; }
    synchronized boolean available(long now, int openMs) {
        return healthy && (circuit == Circuit.CLOSED ||
                (circuit == Circuit.OPEN && now - openedAt >= openMs * 1_000_000L));
    }
    synchronized Ticket acquire(long now, Config config) {
        if (!available(now, config.circuitOpenMs())) return null;
        boolean probe = circuit == Circuit.OPEN;
        if (probe) circuit = Circuit.HALF_OPEN;
        active++;
        attempts.increment();
        return new Ticket(this, generation, probe);
    }
    synchronized void finish(Ticket ticket, boolean success, long now, Config config) {
        active--;
        if (!success) failures.increment();
        // An old in-flight success must not close a circuit opened by a newer failure.
        if (ticket.generation != generation) return;
        if (ticket.probe) {
            if (success) { circuit = Circuit.CLOSED; consecutiveFailures = 0; generation++; }
            else open(now);
        } else if (circuit == Circuit.CLOSED) {
            if (success) consecutiveFailures = 0;
            else if (++consecutiveFailures >= config.circuitFailures()) open(now);
        }
    }
    private void open(long now) { circuit = Circuit.OPEN; openedAt = now; generation++; }
    synchronized void healthResult(boolean success, Config config) {
        if (success) {
            healthBad = 0;
            if (++healthGood >= config.healthSuccesses()) healthy = true;
        } else {
            healthGood = 0;
            if (++healthBad >= config.healthFailures()) healthy = false;
        }
    }
}
