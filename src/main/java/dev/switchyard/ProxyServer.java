package dev.switchyard;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class ProxyServer implements AutoCloseable {
    private record State(Config config, BackendPool pool) {}
    @FunctionalInterface public interface DemoControl { void set(String id, String mode); }
    private final AtomicReference<State> state;
    private final Path configPath;
    private final String adminToken;
    private final DemoControl demoControl;
    private final HttpServer server, admin;
    private final ThreadPoolExecutor ingress, adminExecutor;
    private final ExecutorService outbound;
    private final ScheduledExecutorService healthScheduler;
    private final Set<Backend> probing = ConcurrentHashMap.newKeySet();
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final HttpClient client;
    private final Semaphore capacity, active;
    private final RateLimiter rateLimiter;
    private final Metrics metrics = new Metrics();
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Instant startedAt = Instant.now();

    public ProxyServer(Path path, String token) throws IOException { this(path, token, null); }
    public ProxyServer(Path path, String token, DemoControl control) throws IOException {
        configPath = path.toAbsolutePath();
        Config config = Config.load(path);
        adminToken = token == null ? "" : token;
        demoControl = control;
        if (!InetAddress.getByName(config.adminBind()).isLoopbackAddress() && adminToken.length() < 16)
            throw new IllegalArgumentException("Non-loopback admin listener requires SWITCHYARD_ADMIN_TOKEN (16+ characters)");
        if (!adminToken.isEmpty() && adminToken.length() < 16)
            throw new IllegalArgumentException("Admin token must contain at least 16 characters");
        // JDK HttpServer coarse read/write deadlines; configure before creating the first server.
        System.setProperty("sun.net.httpserver.maxReqTime", "15");
        System.setProperty("sun.net.httpserver.maxRspTime", "15");
        System.setProperty("sun.net.httpserver.maxReqHeaders", "100");
        System.setProperty("jdk.httpclient.disableRetryConnect", "true");
        state = new AtomicReference<>(new State(config, new BackendPool(config, null)));
        capacity = new Semaphore(config.maxConcurrent() + config.maxQueued(), true);
        active = new Semaphore(config.maxConcurrent(), true);
        rateLimiter = new RateLimiter(config.ratePerSecond(), config.rateBurst(), config.rateMaxClients());
        // Extra handler threads can reject excess admission while admitted handlers are busy.
        int handlerCount = config.maxConcurrent() + config.maxQueued() + 4;
        ingress = new ThreadPoolExecutor(handlerCount, handlerCount, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64), HttpSupport.threads("ingress"), new ThreadPoolExecutor.CallerRunsPolicy());
        adminExecutor = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16),
                HttpSupport.threads("admin"), new ThreadPoolExecutor.CallerRunsPolicy());
        outbound = Executors.newFixedThreadPool(Math.max(4, Math.min(32, config.maxConcurrent())), HttpSupport.threads("outbound"));
        healthScheduler = Executors.newSingleThreadScheduledExecutor(HttpSupport.threads("health"));
        client = HttpSupport.client(config.connectTimeoutMs(), outbound);
        server = HttpServer.create(new InetSocketAddress(config.bind(), config.port()), 128);
        HttpServer adminCandidate;
        try { adminCandidate = HttpServer.create(new InetSocketAddress(config.adminBind(), config.adminPort()), 32); }
        catch (IOException e) { server.stop(0); ingress.shutdownNow(); adminExecutor.shutdownNow(); outbound.shutdownNow(); healthScheduler.shutdownNow(); throw e; }
        admin = adminCandidate;
        server.setExecutor(ingress); server.createContext("/", this::handle);
        admin.setExecutor(adminExecutor); admin.createContext("/", this::adminHandle);
    }
    public void start() {
        server.start(); admin.start();
        healthScheduler.scheduleWithFixedDelay(this::checkHealth, 0, state.get().config.healthIntervalMs(), TimeUnit.MILLISECONDS);
    }
    public int port() { return server.getAddress().getPort(); }
    public int adminPort() { return admin.getAddress().getPort(); }

    private void checkHealth() {
        if (closed.get() || draining.get()) return;
        State snapshot = state.get();
        for (Backend backend : snapshot.pool.backends()) {
            if (!probing.add(backend)) continue;
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(backend.origin + snapshot.config.healthPath()))
                        .timeout(Duration.ofMillis(snapshot.config.healthTimeoutMs())).GET().build();
                CompletableFuture<HttpResponse<byte[]>> future = client.sendAsync(request, info -> new HttpSupport.LimitedBody(4096));
                pending.add(future);
                // Separate dependent timeout future so cancellation still reaches the HTTP operation.
                future.copy().orTimeout(snapshot.config.healthTimeoutMs(), TimeUnit.MILLISECONDS).whenComplete((response, error) -> {
                    if (error != null) future.cancel(true);
                    backend.healthResult(error == null && response.statusCode() >= 200 && response.statusCode() < 300, snapshot.config);
                    probing.remove(backend); pending.remove(future);
                });
            } catch (RuntimeException e) { probing.remove(backend); backend.healthResult(false, snapshot.config); }
        }
    }

    private void handle(HttpExchange exchange) {
        long started = System.nanoTime();
        String requestId = UUID.randomUUID().toString();
        exchange.getResponseHeaders().set("X-Request-Id", requestId);
        if (exchange.getRequestHeaders().getOrDefault("Connection", List.of()).stream()
                .flatMap(v -> Arrays.stream(v.split(","))).anyMatch(v -> v.trim().equalsIgnoreCase("close")))
            exchange.getResponseHeaders().set("Connection", "close");
        metrics.requests.increment();
        int status = 500;
        boolean admitted = false, acquired = false;
        try {
            if (draining.get()) { status = 503; HttpSupport.error(exchange, status, "Proxy is draining"); return; }
            String method = exchange.getRequestMethod();
            URI target = exchange.getRequestURI();
            if (method.equals("CONNECT") || method.equals("TRACE") || exchange.getRequestHeaders().containsKey("Upgrade")) {
                status = 501; HttpSupport.error(exchange, status, "CONNECT, TRACE and protocol upgrades are unsupported"); return;
            }
            if (target.isAbsolute() || target.getRawAuthority() != null || target.getRawFragment() != null
                    || target.getRawPath() == null || !target.getRawPath().startsWith("/")) {
                status = 400; HttpSupport.error(exchange, status, "Only origin-form request targets are supported"); return;
            }
            State snapshot = state.get();
            Config config = snapshot.config;
            if (!rateLimiter.allow(exchange.getRemoteAddress().getAddress().getHostAddress())) {
                metrics.rateLimited.increment(); status = 429;
                exchange.getResponseHeaders().set("Retry-After", "" + rateLimiter.retryAfterSeconds());
                HttpSupport.error(exchange, status, "Client rate limit exceeded"); return;
            }
            if (!(admitted = capacity.tryAcquire())) {
                metrics.overloaded.increment(); status = 503; HttpSupport.error(exchange, status, "Proxy capacity exhausted"); return;
            }
            if (!(acquired = active.tryAcquire())) {
                metrics.queued.incrementAndGet();
                try { acquired = active.tryAcquire(config.queueWaitMs(), TimeUnit.MILLISECONDS); }
                finally { metrics.queued.decrementAndGet(); }
                if (!acquired) {
                    metrics.overloaded.increment(); status = 503; HttpSupport.error(exchange, status, "Admission queue deadline exceeded"); return;
                }
            }
            metrics.inFlight.incrementAndGet();
            String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
            if (contentLength != null && Long.parseLong(contentLength) > config.maxRequestBytes()) throw new HttpSupport.BodyTooLarge();
            byte[] body = HttpSupport.readLimited(exchange.getRequestBody(), config.maxRequestBytes());
            metrics.receivedBytes.add(body.length);
            status = forward(exchange, snapshot, body, requestId);
        } catch (HttpSupport.BodyTooLarge e) {
            status = 413; tryError(exchange, status, "Request body exceeds configured limit");
        } catch (IllegalArgumentException e) {
            status = 400; tryError(exchange, status, "Invalid request");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); status = 503; tryError(exchange, status, "Proxy shutting down");
        } catch (IOException e) {
            status = exchange.getResponseCode() < 0 ? 499 : exchange.getResponseCode();
        } catch (Exception e) {
            status = 500; tryError(exchange, status, "Internal proxy error");
            System.err.println("request_id=" + requestId + " failure=" + e.getClass().getSimpleName());
        } finally {
            if (acquired) { metrics.inFlight.decrementAndGet(); active.release(); }
            if (admitted) capacity.release();
            exchange.close(); metrics.completed(status, started);
            System.out.println("{\"type\":\"access\",\"request_id\":" + HttpSupport.json(requestId)
                    + ",\"method\":" + HttpSupport.json(exchange.getRequestMethod()) + ",\"status\":" + status
                    + ",\"duration_ms\":" + ((System.nanoTime() - started) / 1_000_000) + "}");
        }
    }

    private int forward(HttpExchange exchange, State snapshot, byte[] body, String requestId) throws IOException, InterruptedException {
        Config config = snapshot.config;
        String method = exchange.getRequestMethod();
        boolean retryable = body.length == 0 && (method.equals("GET") || method.equals("HEAD"));
        Set<String> excluded = new HashSet<>();
        int lastError = 503;
        for (int attempt = 0; attempt <= (retryable ? config.retries() : 0); attempt++) {
            Backend.Ticket ticket = snapshot.pool.acquire(config, excluded);
            if (ticket == null) break;
            Backend backend = ticket.backend();
            excluded.add(backend.id);
            if (attempt > 0) metrics.retries.increment();
            HttpResponse<byte[]> response = null;
            boolean success = false;
            CompletableFuture<HttpResponse<byte[]>> future = null;
            try {
                HttpRequest request = buildRequest(exchange, config, backend, body, requestId);
                future = client.sendAsync(request, info -> new HttpSupport.LimitedBody(config.maxResponseBytes()));
                pending.add(future);
                response = future.get(config.requestTimeoutMs(), TimeUnit.MILLISECONDS);
                success = response.statusCode() < 500;
            } catch (TimeoutException e) {
                lastError = 504;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                lastError = cause instanceof HttpTimeoutException ? 504 : 502;
            } catch (CancellationException e) {
                lastError = 503;
            } finally {
                if (future != null) { if (!future.isDone()) future.cancel(true); pending.remove(future); }
                backend.finish(ticket, success, System.nanoTime(), config);
            }
            if (response == null) continue;
            // Do not consume the last useful response unless a retry target exists.
            if (retryable && attempt < config.retries() && Set.of(502, 503, 504).contains(response.statusCode())
                    && snapshot.pool.backends().stream().anyMatch(b -> !excluded.contains(b.id)
                    && b.available(System.nanoTime(), config.circuitOpenMs()))) continue;
            return relay(exchange, response, backend.id);
        }
        HttpSupport.error(exchange, lastError, switch (lastError) {
            case 504 -> "Upstream deadline exceeded";
            case 502 -> "Upstream connection, protocol or response-size failure";
            default -> "No available backend";
        });
        return lastError;
    }

    private HttpRequest buildRequest(HttpExchange exchange, Config config, Backend backend, byte[] body, String requestId) {
        String raw = exchange.getRequestURI().getRawPath();
        if (exchange.getRequestURI().getRawQuery() != null) raw += "?" + exchange.getRequestURI().getRawQuery();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(backend.origin + raw))
                .timeout(Duration.ofMillis(config.requestTimeoutMs()))
                .method(exchange.getRequestMethod(), body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        Set<String> blocked = HttpSupport.blocked(exchange.getRequestHeaders());
        exchange.getRequestHeaders().forEach((key, values) -> {
            String lower = key.toLowerCase(Locale.ROOT);
            if (!blocked.contains(lower) && !Set.of("host", "content-length", "expect", "forwarded", "x-request-id").contains(lower)
                    && !lower.startsWith("x-forwarded-")) for (String value : values) builder.header(key, value);
        });
        builder.header("X-Forwarded-For", exchange.getRemoteAddress().getAddress().getHostAddress());
        builder.header("X-Forwarded-Proto", "http");
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host != null) builder.header("X-Forwarded-Host", host);
        builder.header("X-Request-Id", requestId);
        builder.header("Via", "1.1 switchyard");
        return builder.build();
    }
    private int relay(HttpExchange exchange, HttpResponse<byte[]> response, String backendId) throws IOException {
        Set<String> blocked = HttpSupport.blocked(response.headers().map());
        response.headers().map().forEach((key, values) -> {
            String lower = key.toLowerCase(Locale.ROOT);
            if (!blocked.contains(lower) && !Set.of("content-length", "x-request-id", "x-switchyard-backend").contains(lower))
                exchange.getResponseHeaders().put(key, new ArrayList<>(values));
        });
        exchange.getResponseHeaders().set("X-Switchyard-Backend", backendId);
        exchange.getResponseHeaders().add("Via", "1.1 switchyard");
        int code = response.statusCode();
        boolean noBody = exchange.getRequestMethod().equals("HEAD") || code == 204 || code == 304 || code == 205;
        if (exchange.getRequestMethod().equals("HEAD") || code == 304)
            response.headers().firstValue("Content-Length").ifPresent(v -> exchange.getResponseHeaders().set("Content-Length", v));
        if (code == 205) exchange.getResponseHeaders().set("Content-Length", "0");
        byte[] bytes = response.body();
        exchange.sendResponseHeaders(code, noBody || bytes.length == 0 ? -1 : bytes.length);
        if (!noBody && bytes.length > 0) { exchange.getResponseBody().write(bytes); metrics.sentBytes.add(bytes.length); }
        return code;
    }

    private boolean authorized(HttpExchange exchange) {
        String supplied = exchange.getRequestHeaders().getFirst("Authorization");
        return !adminToken.isEmpty() && supplied != null && MessageDigest.isEqual(
                ("Bearer " + adminToken).getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }
    private void adminHandle(HttpExchange exchange) {
        try {
            String path = exchange.getRequestURI().getPath(), method = exchange.getRequestMethod();
            boolean read = method.equals("GET") || method.equals("HEAD");
            boolean local = exchange.getRemoteAddress().getAddress().isLoopbackAddress();
            if (!read && !authorized(exchange)) { HttpSupport.error(exchange, 401, "A configured admin bearer token is required"); return; }
            // The static login/dashboard shell contains no operational data; its API calls require auth remotely.
            if (read && !path.equals("/") && !local && !authorized(exchange)) { HttpSupport.error(exchange, 401, "Admin bearer token required"); return; }
            if (read && path.equals("/")) {
                try (InputStream in = ProxyServer.class.getResourceAsStream("/dashboard.html")) {
                    if (in == null) throw new IOException("Missing dashboard resource");
                    exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'");
                    HttpSupport.send(exchange, 200, "text/html; charset=utf-8", new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            } else if (read && path.equals("/healthz")) HttpSupport.send(exchange, 200, "application/json", "{\"alive\":true}");
            else if (read && path.equals("/readyz")) {
                State snapshot = state.get();
                boolean ready = !draining.get() && snapshot.pool.backends().stream().anyMatch(b -> b.available(System.nanoTime(), snapshot.config.circuitOpenMs()));
                HttpSupport.send(exchange, ready ? 200 : 503, "application/json", "{\"ready\":" + ready + "}");
            } else if (read && path.equals("/api/status")) HttpSupport.send(exchange, 200, "application/json", statusJson());
            else if (read && path.equals("/metrics")) HttpSupport.send(exchange, 200, "text/plain; version=0.0.4; charset=utf-8", metrics.prometheus(state.get().pool));
            else if (method.equals("POST") && path.equals("/api/reload")) {
                HttpSupport.readLimited(exchange.getRequestBody(), 1024);
                reload(); HttpSupport.send(exchange, 200, "application/json", "{\"reloaded\":true}");
            } else if (method.equals("POST") && path.equals("/api/demo") && demoControl != null) {
                String input = new String(HttpSupport.readLimited(exchange.getRequestBody(), 1024), StandardCharsets.UTF_8);
                Map<String, String> form = new HashMap<>();
                for (String field : input.split("&")) {
                    String[] pair = field.split("=", 2);
                    if (pair.length == 2) form.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
                }
                demoControl.set(form.getOrDefault("id", ""), form.getOrDefault("mode", ""));
                HttpSupport.send(exchange, 200, "application/json", "{\"updated\":true}");
            } else HttpSupport.error(exchange, 404, "Unknown admin route or method");
        } catch (IllegalArgumentException e) { tryError(exchange, 400, e.getMessage()); }
        catch (Exception e) { tryError(exchange, 500, "Admin operation failed: " + e.getClass().getSimpleName()); }
        finally { exchange.close(); }
    }
    public synchronized void reload() throws IOException {
        State previous = state.get();
        Config candidate = Config.load(configPath);
        if (!previous.config.sameStaticSettings(candidate))
            throw new IllegalArgumentException("Reload supports only balance.algorithm and backend ids/URLs/weights; other changes require restart");
        // Backends cannot point into either listener (obvious local recursion).
        validateNoSelf(candidate);
        state.set(new State(candidate, new BackendPool(candidate, previous.pool)));
        checkHealth();
    }
    public void validateNoSelf(Config config) {
        for (Config.BackendSpec backend : config.backends()) {
            int port = backend.origin().getPort();
            try {
                InetAddress address = InetAddress.getByName(backend.origin().getHost());
                if ((port == port() || port == adminPort()) && (address.isLoopbackAddress() || address.isAnyLocalAddress()))
                    throw new IllegalArgumentException("Backend cannot point to a proxy/admin listener");
            } catch (UnknownHostException ignored) { /* Health probes mark unresolved origins unhealthy. */ }
        }
    }
    private String statusJson() {
        State snapshot = state.get();
        StringBuilder out = new StringBuilder("{\"name\":\"Switchyard\",\"algorithm\":")
                .append(HttpSupport.json(snapshot.config.algorithm())).append(",\"startedAt\":").append(HttpSupport.json(startedAt.toString()))
                .append(",\"proxyPort\":").append(port()).append(",\"demo\":").append(demoControl != null)
                .append(",\"draining\":").append(draining.get()).append(",\"requests\":").append(metrics.requests.sum())
                .append(",\"inFlight\":").append(metrics.inFlight.get()).append(",\"queued\":").append(metrics.queued.get())
                .append(",\"rateLimited\":").append(metrics.rateLimited.sum()).append(",\"retries\":").append(metrics.retries.sum())
                .append(",\"overloaded\":").append(metrics.overloaded.sum()).append(",\"backends\":[");
        boolean comma = false;
        for (Backend backend : snapshot.pool.backends()) {
            if (comma) out.append(','); comma = true;
            out.append("{\"id\":").append(HttpSupport.json(backend.id)).append(",\"origin\":").append(HttpSupport.json(backend.origin.toString()))
                    .append(",\"healthy\":").append(backend.healthy()).append(",\"circuit\":").append(HttpSupport.json(backend.circuit().name()))
                    .append(",\"active\":").append(backend.active()).append(",\"attempts\":").append(backend.attempts.sum())
                    .append(",\"failures\":").append(backend.failures.sum()).append('}');
        }
        return out.append("]}").toString();
    }
    private static void tryError(HttpExchange exchange, int status, String message) {
        try { if (exchange.getResponseCode() == -1) HttpSupport.error(exchange, status, message); }
        catch (IOException ignored) { /* Peer may already have disconnected. */ }
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        draining.set(true); healthScheduler.shutdownNow();
        server.stop(state.get().config.shutdownSeconds());
        pending.forEach(future -> future.cancel(true));
        admin.stop(0); ingress.shutdownNow(); adminExecutor.shutdownNow(); outbound.shutdownNow();
    }
}
