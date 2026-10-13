package dev.switchyard;

import java.nio.file.*;
import java.util.concurrent.CountDownLatch;

public final class Main {
    public static void main(String[] args) throws Exception {
        Path config = Path.of(args.length == 0 ? "config/local.properties" : args[0]);
        ProxyServer proxy = new ProxyServer(config, System.getenv("SWITCHYARD_ADMIN_TOKEN"));
        try { proxy.validateNoSelf(Config.load(config)); } catch (Exception e) { proxy.close(); throw e; }
        Runtime.getRuntime().addShutdownHook(new Thread(proxy::close, "shutdown"));
        proxy.start();
        System.out.println("Switchyard proxy listening on port " + proxy.port() + "; admin on " + proxy.adminPort());
        new CountDownLatch(1).await();
    }
}
