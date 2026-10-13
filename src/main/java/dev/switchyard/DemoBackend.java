package dev.switchyard;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Local fixture; delay/failure controls are exposed only through DemoMain's authenticated admin. */
public final class DemoBackend implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;
    final AtomicInteger delayMs = new AtomicInteger(), status = new AtomicInteger(200), count = new AtomicInteger();
    volatile boolean healthy = true;
    final String id;
    public DemoBackend(String bind, int port, String id) throws IOException {
        this.id = id;
        server = HttpServer.create(new InetSocketAddress(bind, port), 64);
        executor = Executors.newFixedThreadPool(16, HttpSupport.threads("demo-" + id));
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }
    public int port() { return server.getAddress().getPort(); }
    void mode(String mode) {
        switch (mode) {
            case "healthy" -> { delayMs.set(0); status.set(200); healthy = true; }
            case "slow" -> { delayMs.set(3000); status.set(200); healthy = true; }
            case "errors" -> { delayMs.set(0); status.set(503); healthy = true; }
            case "unhealthy" -> { delayMs.set(0); healthy = false; }
            default -> throw new IllegalArgumentException("Unknown demo mode");
        }
    }
    private void handle(HttpExchange exchange) {
        try {
            if (exchange.getRequestURI().getPath().equals("/health")) {
                HttpSupport.send(exchange, healthy ? 200 : 503, "application/json", "{\"healthy\":" + healthy + "}"); return;
            }
            count.incrementAndGet();
            if (delayMs.get() > 0) Thread.sleep(delayMs.get());
            byte[] body = HttpSupport.readLimited(exchange.getRequestBody(), 16777216);
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/echo")) {
                exchange.getResponseHeaders().add("Set-Cookie", "one=1; HttpOnly");
                exchange.getResponseHeaders().add("Set-Cookie", "two=2; HttpOnly");
                exchange.getResponseHeaders().set("X-Seen-Forwarded-For", String.valueOf(exchange.getRequestHeaders().getFirst("X-Forwarded-For")));
                exchange.getResponseHeaders().set("X-Seen-Hop", String.valueOf(exchange.getRequestHeaders().getFirst("X-Hop-Test")));
                exchange.getResponseHeaders().set("Connection", "X-Hop-Response");
                exchange.getResponseHeaders().set("X-Hop-Response", "must-not-leak");
                exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                exchange.sendResponseHeaders(status.get(), body.length == 0 ? -1 : body.length);
                if (body.length > 0) exchange.getResponseBody().write(body);
            } else if (path.equals("/redirect")) {
                exchange.getResponseHeaders().set("Location", "/destination"); exchange.sendResponseHeaders(302, -1);
            } else if (path.equals("/empty")) exchange.sendResponseHeaders(204, -1);
            else if (path.equals("/large")) HttpSupport.send(exchange, 200, "text/plain", "x".repeat(8192));
            else HttpSupport.send(exchange, status.get(), "application/json; charset=utf-8",
                    "{\"backend\":" + HttpSupport.json(id) + ",\"method\":" + HttpSupport.json(exchange.getRequestMethod())
                    + ",\"uri\":" + HttpSupport.json(exchange.getRequestURI().toString()) + ",\"bodyBytes\":" + body.length
                    + ",\"message\":\"Hello from Switchyard\"}");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (IOException ignored) { /* Timeout demos deliberately disconnect. */ }
        finally { exchange.close(); }
    }
    public void close() { server.stop(0); executor.shutdownNow(); }
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9001;
        String id = args.length > 1 ? args[1] : "demo";
        DemoBackend backend = new DemoBackend(args.length > 2 ? args[2] : "127.0.0.1", port, id);
        Runtime.getRuntime().addShutdownHook(new Thread(backend::close));
        System.out.println("Demo backend " + id + " on port " + backend.port());
        new CountDownLatch(1).await();
    }
}
