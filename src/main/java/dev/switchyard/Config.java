package dev.switchyard;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/** Validated configuration. A complete candidate is validated before a reload is published. */
public record Config(String bind, int port, String adminBind, int adminPort,
        int maxConcurrent, int maxQueued, int queueWaitMs, int requestTimeoutMs,
        int connectTimeoutMs, int maxRequestBytes, int maxResponseBytes,
        int healthIntervalMs, int healthTimeoutMs, int healthFailures, int healthSuccesses,
        int circuitFailures, int circuitOpenMs, int retries, int shutdownSeconds,
        double ratePerSecond, int rateBurst, int rateMaxClients, String algorithm,
        String healthPath, List<BackendSpec> backends) {
    public record BackendSpec(String id, URI origin, int weight) {}
    public static Config load(Path path) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(path)) { p.load(r); }
        return from(p);
    }
    public static Config from(Properties p) {
        Set<String> keys = Set.of("listen.bind", "listen.port", "admin.bind", "admin.port",
                "proxy.maxConcurrent", "proxy.maxQueued", "proxy.queueWaitMs", "proxy.requestTimeoutMs",
                "proxy.connectTimeoutMs", "proxy.maxRequestBytes", "proxy.maxResponseBytes",
                "health.intervalMs", "health.timeoutMs", "health.failures", "health.successes", "health.path",
                "circuit.failures", "circuit.openMs", "proxy.retries", "shutdown.seconds",
                "rate.perSecond", "rate.burst", "rate.maxClients", "balance.algorithm", "backends");
        for (String key : p.stringPropertyNames()) {
            if (!keys.contains(key) && !key.matches("backend\\.[A-Za-z0-9_-]+\\.(url|weight)"))
                throw new IllegalArgumentException("Unknown property: " + key);
        }
        String algorithm = p.getProperty("balance.algorithm", "round_robin");
        if (!Set.of("round_robin", "weighted_round_robin", "least_connections").contains(algorithm))
            throw new IllegalArgumentException("Invalid balance.algorithm");
        String healthPath = p.getProperty("health.path", "/health");
        if (!healthPath.startsWith("/") || healthPath.startsWith("//") || healthPath.contains("#"))
            throw new IllegalArgumentException("health.path must be an origin-relative path");
        URI.create(healthPath);
        List<BackendSpec> specs = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (String part : p.getProperty("backends", "").split(",")) {
            String id = part.trim();
            if (!id.matches("[A-Za-z0-9_-]{1,40}") || !ids.add(id))
                throw new IllegalArgumentException("Invalid or duplicate backend id: " + id);
            URI uri = URI.create(p.getProperty("backend." + id + ".url", ""));
            if (!Set.of("http", "https").contains(Objects.toString(uri.getScheme(), "")) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))
                    || uri.getPort() == 0 || uri.getPort() > 65535)
                throw new IllegalArgumentException("Backend URL must be an http(s) origin: " + id);
            URI origin = URI.create(uri.getScheme() + "://" + uri.getRawAuthority());
            specs.add(new BackendSpec(id, origin, integer(p, "backend." + id + ".weight", 1, 1, 100)));
        }
        if (specs.isEmpty() || specs.size() > 32) throw new IllegalArgumentException("Use 1 to 32 backends");
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith("backend.") && !ids.contains(key.split("\\.")[1]))
                throw new IllegalArgumentException("Property references unlisted backend: " + key);
        }
        double rate;
        try { rate = Double.parseDouble(p.getProperty("rate.perSecond", "100")); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid rate.perSecond"); }
        if (!Double.isFinite(rate) || rate < 0 || rate > 1_000_000)
            throw new IllegalArgumentException("rate.perSecond must be 0..1000000 (0 disables)");
        return new Config(p.getProperty("listen.bind", "127.0.0.1"), integer(p, "listen.port", 8080, 0, 65535),
                p.getProperty("admin.bind", "127.0.0.1"), integer(p, "admin.port", 9090, 0, 65535),
                integer(p, "proxy.maxConcurrent", 32, 1, 256), integer(p, "proxy.maxQueued", 32, 0, 256),
                integer(p, "proxy.queueWaitMs", 500, 1, 60000), integer(p, "proxy.requestTimeoutMs", 2000, 50, 60000),
                integer(p, "proxy.connectTimeoutMs", 500, 20, 30000),
                integer(p, "proxy.maxRequestBytes", 1048576, 1, 16777216),
                integer(p, "proxy.maxResponseBytes", 4194304, 1, 16777216),
                integer(p, "health.intervalMs", 1000, 50, 60000), integer(p, "health.timeoutMs", 400, 20, 30000),
                integer(p, "health.failures", 2, 1, 100), integer(p, "health.successes", 2, 1, 100),
                integer(p, "circuit.failures", 3, 1, 1000), integer(p, "circuit.openMs", 3000, 50, 300000),
                integer(p, "proxy.retries", 1, 0, 3), integer(p, "shutdown.seconds", 5, 0, 60),
                rate, integer(p, "rate.burst", 200, 1, 1000000), integer(p, "rate.maxClients", 10000, 1, 100000),
                algorithm, healthPath, List.copyOf(specs));
    }
    private static int integer(Properties p, String key, int fallback, int min, int max) {
        int value;
        try { value = Integer.parseInt(p.getProperty(key, "" + fallback)); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid integer: " + key); }
        if (value < min || value > max) throw new IllegalArgumentException(key + " must be " + min + ".." + max);
        return value;
    }
    /** Listener, resource and timeout policy changes require a restart. */
    public boolean sameStaticSettings(Config other) {
        return new Config(bind, port, adminBind, adminPort, maxConcurrent, maxQueued, queueWaitMs,
                requestTimeoutMs, connectTimeoutMs, maxRequestBytes, maxResponseBytes, healthIntervalMs,
                healthTimeoutMs, healthFailures, healthSuccesses, circuitFailures, circuitOpenMs, retries,
                shutdownSeconds, ratePerSecond, rateBurst, rateMaxClients, "", healthPath, List.of()).equals(
                new Config(other.bind, other.port, other.adminBind, other.adminPort, other.maxConcurrent,
                other.maxQueued, other.queueWaitMs, other.requestTimeoutMs, other.connectTimeoutMs,
                other.maxRequestBytes, other.maxResponseBytes, other.healthIntervalMs, other.healthTimeoutMs,
                other.healthFailures, other.healthSuccesses, other.circuitFailures, other.circuitOpenMs,
                other.retries, other.shutdownSeconds, other.ratePerSecond, other.rateBurst, other.rateMaxClients,
                "", other.healthPath, List.of()));
    }
}
