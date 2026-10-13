package dev.switchyard;

import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.CountDownLatch;

public final class DemoMain {
    public static void main(String[] args) throws Exception {
        // Set HTTP limits before the demo creates the first JDK HttpServer.
        System.setProperty("sun.net.httpserver.maxReqTime", "15");
        System.setProperty("sun.net.httpserver.maxRspTime", "15");
        Path path = Path.of(args.length == 0 ? "config/local.properties" : args[0]);
        Config config = Config.load(path);
        Map<String, DemoBackend> backends = new LinkedHashMap<>();
        try {
            for (Config.BackendSpec spec : config.backends()) {
                if (!java.net.InetAddress.getByName(spec.origin().getHost()).isLoopbackAddress())
                    throw new IllegalArgumentException("Demo requires loopback backend origins");
                backends.put(spec.id(), new DemoBackend("127.0.0.1", spec.origin().getPort(), spec.id()));
            }
            String token = System.getenv("SWITCHYARD_ADMIN_TOKEN");
            if (token == null || token.isBlank()) {
                byte[] bytes = new byte[24]; new SecureRandom().nextBytes(bytes);
                token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            }
            ProxyServer proxy = new ProxyServer(path, token, (id, mode) -> {
                DemoBackend backend = backends.get(id);
                if (backend == null) throw new IllegalArgumentException("Unknown demo backend");
                backend.mode(mode);
            });
            proxy.validateNoSelf(config);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> { proxy.close(); backends.values().forEach(DemoBackend::close); }));
            proxy.start();
            System.out.println("Dashboard: http://127.0.0.1:" + proxy.adminPort());
            System.out.println("Proxy:     http://127.0.0.1:" + proxy.port());
            System.out.println("Local demo admin token (paste into dashboard): " + token);
            new CountDownLatch(1).await();
        } catch (Exception e) { backends.values().forEach(DemoBackend::close); throw e; }
    }
}
