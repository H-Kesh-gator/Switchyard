package dev.switchyard;

import java.util.*;

/** Selection and reservation are one atomic operation for least-connections accuracy. */
final class BackendPool {
    private static final class Entry {
        final Backend backend;
        final int weight;
        long current;
        Entry(Backend backend, int weight) { this.backend = backend; this.weight = weight; }
    }
    private final List<Entry> entries;
    private long cursor;
    BackendPool(Config config, BackendPool previous) {
        entries = new ArrayList<>();
        for (Config.BackendSpec spec : config.backends()) {
            Backend backend = previous == null ? null : previous.backends().stream()
                    .filter(b -> b.id.equals(spec.id()) && b.origin.equals(spec.origin())).findFirst().orElse(null);
            entries.add(new Entry(backend == null ? new Backend(spec) : backend, spec.weight()));
        }
    }
    List<Backend> backends() { return entries.stream().map(e -> e.backend).toList(); }
    synchronized Backend.Ticket acquire(Config config, Set<String> excluded) {
        long now = System.nanoTime();
        List<Entry> available = entries.stream().filter(e -> !excluded.contains(e.backend.id)
                && e.backend.available(now, config.circuitOpenMs())).toList();
        if (available.isEmpty()) return null;
        Entry chosen;
        if (config.algorithm().equals("weighted_round_robin")) {
            long total = 0;
            chosen = null;
            for (Entry entry : entries) {
                if (!available.contains(entry)) { entry.current = 0; continue; }
                entry.current += entry.weight;
                total += entry.weight;
                if (chosen == null || entry.current > chosen.current) chosen = entry;
            }
            chosen.current -= total;
        } else {
            int start = (int) Math.floorMod(cursor++, (long) entries.size());
            chosen = null;
            for (int i = 0; i < entries.size(); i++) {
                Entry entry = entries.get((start + i) % entries.size());
                if (!available.contains(entry)) continue;
                if (chosen == null || (config.algorithm().equals("least_connections")
                        && entry.backend.active() < chosen.backend.active())) chosen = entry;
                if (config.algorithm().equals("round_robin")) break;
            }
        }
        return chosen.backend.acquire(now, config);
    }
}
