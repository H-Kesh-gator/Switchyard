package dev.switchyard;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

final class HttpSupport {
    static final Set<String> HOP = Set.of("connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "proxy-connection");
    static Set<String> blocked(Map<String, List<String>> headers) {
        Set<String> result = new HashSet<>(HOP);
        headers.forEach((key, values) -> {
            if (key.equalsIgnoreCase("connection")) for (String value : values)
                for (String token : value.split(",")) result.add(token.trim().toLowerCase(Locale.ROOT));
        });
        return result;
    }
    static String json(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
                default -> { if (c < 32) out.append(String.format("\\u%04x", (int)c)); else out.append(c); }
            }
        }
        return out.append('"').toString();
    }
    static void send(HttpExchange exchange, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.getResponseHeaders().set("Content-Length", "" + bytes.length);
            exchange.sendResponseHeaders(code, -1);
        } else {
            exchange.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
        }
    }
    static void error(HttpExchange e, int status, String message) throws IOException {
        // Rejections may happen before the incoming body is consumed: do not reuse that connection.
        e.getResponseHeaders().set("Connection", "close");
        send(e, status, "application/json; charset=utf-8", "{\"error\":" + json(message) + "}");
    }
    static byte[] readLimited(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(max, 8192));
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (n > max - out.size()) throw new BodyTooLarge();
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
    static final class BodyTooLarge extends IOException { private static final long serialVersionUID = 1L; }
    static HttpClient client(int connectTimeoutMs, Executor executor) {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .executor(executor).proxy(new ProxySelector() {
                    public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    public void connectFailed(URI uri, SocketAddress address, IOException error) {}
                }).build();
    }
    /** Caps upstream response memory while receiving, instead of checking after allocation. */
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final int limit;
        private Flow.Subscription subscription;
        LimitedBody(int limit) { this.limit = limit; }
        public CompletionStage<byte[]> getBody() { return body; }
        public void onSubscribe(Flow.Subscription s) { subscription = s; s.request(1); }
        public void onNext(List<ByteBuffer> items) {
            for (ByteBuffer item : items) {
                if (item.remaining() > limit - out.size()) {
                    subscription.cancel(); body.completeExceptionally(new BodyTooLarge()); return;
                }
                byte[] bytes = new byte[item.remaining()]; item.get(bytes); out.writeBytes(bytes);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { body.completeExceptionally(error); }
        public void onComplete() { body.complete(out.toByteArray()); }
    }
    static ThreadFactory threads(String prefix) {
        java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();
        return r -> { Thread t = new Thread(r, prefix + "-" + count.incrementAndGet()); t.setDaemon(true); return t; };
    }
}
