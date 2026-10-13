package dev.switchyard;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Standalone assertions keep the entire build/test path offline and dependency-free. */
public final class AllTests {
    private static final String TOKEN = "test-only-token-123456789";
    private static final HttpClient CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(2)).build();
    private static int passed;
    @FunctionalInterface interface Test { void run() throws Exception; }
    public static void main(String[] args) throws Exception {
        System.setProperty("sun.net.httpserver.maxReqTime", "15");
        System.setProperty("sun.net.httpserver.maxRspTime", "15");
        run("configuration validation rejects malformed/unknown input", AllTests::configValidation);
        run("round-robin distributes equally", AllTests::roundRobin);
        run("smooth weighted round-robin honours 3:1:1", AllTests::weighted);
        run("least-connections reserves atomically and balances ties", AllTests::leastConnections);
        run("circuit opens, permits one probe, and ignores stale success", AllTests::circuit);
        run("token bucket refills and bounds client cardinality", AllTests::rateUnit);
        run("binary bodies, duplicate cookies and safe forwarding headers", AllTests::forwarding);
        run("encoded path/query, HEAD, 204 and redirects", AllTests::semantics);
        run("connection-nominated headers removed in both directions", AllTests::hopHeaders);
        run("oversized known and chunked request bodies rejected", AllTests::requestLimit);
        run("oversized upstream response rejected before unbounded allocation", AllTests::responseLimit);
        run("GET retries a different backend after 503", AllTests::getRetry);
        run("POST failure is never automatically replayed", AllTests::postNoRetry);
        run("body-bearing GET is not automatically replayed", AllTests::getBodyNoRetry);
        run("healthy -> unhealthy -> recovered routing", AllTests::healthRecovery);
        run("circuit isolates 503s even when health checks pass", AllTests::circuitIntegration);
        run("full response deadline includes stalled response bodies", AllTests::bodyTimeout);
        run("rate limiting returns 429 and Retry-After", AllTests::rateIntegration);
        run("bounded capacity rejects excess work and later recovers", AllTests::overload);
        run("bounded admission queue expires with 503", AllTests::queueTimeout);
        run("reload preserves existing state and rejects invalid config atomically", AllTests::reload);
        run("reload introduces a new backend only after health succeeds", AllTests::reloadNewBackend);
        run("admin mutations require bearer token", AllTests::adminAuth);
        run("CONNECT, TRACE, upgrade and absolute-form targets rejected", AllTests::unsupported);
        run("metrics and dashboard endpoints render", AllTests::observability);
        run("graceful shutdown finishes an admitted request", AllTests::gracefulShutdown);
        System.out.println("PASS: " + passed + " tests");
    }
    private static void run(String name, Test test) throws Exception {
        try { test.run(); passed++; System.out.println("PASS " + name); }
        catch (Throwable e) { System.err.println("FAIL " + name); throw e; }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void equal(Object wanted, Object got) { check(Objects.equals(wanted, got), "Expected " + wanted + ", got " + got); }
    private static void rejects(Test operation) throws Exception {
        try { operation.run(); throw new AssertionError("Expected IllegalArgumentException"); }
        catch (IllegalArgumentException expected) { }
    }
    private static Properties props() {
        Properties p = new Properties(); p.setProperty("backends", "a,b,c");
        for (int i = 0; i < 3; i++) p.setProperty("backend." + (char)('a' + i) + ".url", "http://127.0.0.1:" + (19001 + i));
        p.setProperty("health.successes", "1"); return p;
    }
    private static BackendPool healthyPool(Config c) {
        BackendPool pool = new BackendPool(c, null);
        pool.backends().forEach(b -> b.healthResult(true, c)); return pool;
    }
    private static void finish(Backend.Ticket t, Config c) { t.backend().finish(t, true, System.nanoTime(), c); }
    private static void configValidation() throws Exception {
        Properties p = props(); p.setProperty("backend.a.url", "http://example.com/private"); rejects(() -> Config.from(p));
        p.setProperty("backend.a.url", "http://127.0.0.1:19001"); p.setProperty("rate.perSecond", "NaN"); rejects(() -> Config.from(p));
        p.setProperty("rate.perSecond", "0"); p.setProperty("typo", "true"); rejects(() -> Config.from(p));
        p.remove("typo"); p.setProperty("backends", "a,a"); rejects(() -> Config.from(p));
        p.setProperty("backends", "a,b,c"); p.setProperty("backend.x.url", "http://127.0.0.1:9999"); rejects(() -> Config.from(p));
    }
    private static void roundRobin() {
        Config c = Config.from(props()); BackendPool pool = healthyPool(c); Map<String,Integer> count = new HashMap<>();
        for (int i = 0; i < 30; i++) { Backend.Ticket t = pool.acquire(c, Set.of()); count.merge(t.backend().id, 1, Integer::sum); finish(t, c); }
        equal(Map.of("a", 10, "b", 10, "c", 10), count);
    }
    private static void weighted() {
        Properties p = props(); p.setProperty("balance.algorithm", "weighted_round_robin"); p.setProperty("backend.a.weight", "3");
        Config c = Config.from(p); BackendPool pool = healthyPool(c); Map<String,Integer> count = new HashMap<>();
        for (int i = 0; i < 50; i++) { Backend.Ticket t = pool.acquire(c, Set.of()); count.merge(t.backend().id, 1, Integer::sum); finish(t, c); }
        equal(Map.of("a", 30, "b", 10, "c", 10), count);
    }
    private static void leastConnections() {
        Properties p = props(); p.setProperty("balance.algorithm", "least_connections"); Config c = Config.from(p); BackendPool pool = healthyPool(c);
        Backend.Ticket a = pool.acquire(c, Set.of()), b = pool.acquire(c, Set.of()), d = pool.acquire(c, Set.of());
        equal(3, Set.of(a.backend().id, b.backend().id, d.backend().id).size()); finish(b, c);
        Backend.Ticket e = pool.acquire(c, Set.of()); equal(b.backend().id, e.backend().id); finish(a,c); finish(d,c); finish(e,c);
    }
    private static void circuit() {
        Properties p = props(); p.setProperty("circuit.failures", "1"); p.setProperty("circuit.openMs", "100"); Config c = Config.from(p);
        Backend b = healthyPool(c).backends().get(0); long now = System.nanoTime();
        Backend.Ticket old = b.acquire(now,c), failed = b.acquire(now,c);
        b.finish(failed,false,now,c); b.finish(old,true,now,c); equal(Backend.Circuit.OPEN,b.circuit());
        check(b.acquire(now+50_000_000,c)==null,"cooldown must block");
        Backend.Ticket probe=b.acquire(now+101_000_000,c); check(probe!=null && probe.probe(),"need probe");
        check(b.acquire(now+102_000_000,c)==null,"only one half-open probe"); b.finish(probe,false,now+102_000_000,c);
        equal(Backend.Circuit.OPEN,b.circuit()); probe=b.acquire(now+203_000_000,c); b.finish(probe,true,now+204_000_000,c);
        equal(Backend.Circuit.CLOSED,b.circuit()); equal(0,b.active());
    }
    private static void rateUnit() {
        AtomicLong time=new AtomicLong(); RateLimiter r=new RateLimiter(2,2,1,time::get);
        check(r.allow("a")&&r.allow("a")&&!r.allow("a"),"burst limit"); check(!r.allow("b"),"new IP must not evict depleted bucket");
        time.addAndGet(500_000_000L); check(r.allow("a"),"refill"); time.addAndGet(1_000_000_000L); check(r.allow("b"),"evict refilled idle bucket");
    }
    private static final class Fixture implements AutoCloseable {
        final List<DemoBackend> backends = new ArrayList<>();
        final Properties properties = new Properties();
        final Path path;
        final ProxyServer proxy;
        Fixture(int count, Map<String,String> overrides) throws Exception {
            for(int i=0;i<count;i++) backends.add(new DemoBackend("127.0.0.1",0,"b"+i));
            properties.setProperty("backends",String.join(",",backends.stream().map(b->b.id).toList()));
            for(DemoBackend b:backends) properties.setProperty("backend."+b.id+".url","http://127.0.0.1:"+b.port());
            properties.setProperty("listen.port","0"); properties.setProperty("admin.port","0");
            properties.setProperty("health.intervalMs","50"); properties.setProperty("health.timeoutMs","100");
            properties.setProperty("health.successes","1"); properties.setProperty("health.failures","1");
            properties.setProperty("proxy.requestTimeoutMs","500"); properties.setProperty("proxy.connectTimeoutMs","100");
            properties.setProperty("proxy.retries","0"); properties.setProperty("rate.perSecond","0");
            properties.setProperty("shutdown.seconds","0"); overrides.forEach(properties::setProperty);
            path=Files.createTempFile("switchyard-test-",".properties"); save();
            proxy=new ProxyServer(path,TOKEN); proxy.start();
            await(()->{ try { return admin("/api/status").body().split("\"healthy\":true",-1).length-1==count; } catch(Exception e){return false;} },3000);
        }
        void save() throws IOException { try(Writer out=Files.newBufferedWriter(path)){properties.store(out,"test");} }
        URI uri(String path){return URI.create("http://127.0.0.1:"+proxy.port()+path);}
        HttpResponse<String> get(String path)throws Exception{return CLIENT.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(4)).GET().build(),HttpResponse.BodyHandlers.ofString());}
        HttpResponse<String> admin(String path)throws Exception{return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+proxy.adminPort()+path)).timeout(Duration.ofSeconds(3)).GET().build(),HttpResponse.BodyHandlers.ofString());}
        HttpResponse<String> adminPost(String path,boolean auth)throws Exception{
            HttpRequest.Builder b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+proxy.adminPort()+path)).POST(HttpRequest.BodyPublishers.noBody());
            if(auth)b.header("Authorization","Bearer "+TOKEN);return CLIENT.send(b.build(),HttpResponse.BodyHandlers.ofString());
        }
        public void close() {proxy.close();backends.forEach(DemoBackend::close);try{Files.deleteIfExists(path);}catch(IOException ignored){}}
    }
    private static void forwarding()throws Exception{
        try(Fixture f=new Fixture(1,Map.of())){
            byte[] data=new byte[]{0,1,2,(byte)255,13,10};
            HttpResponse<byte[]> r=CLIENT.send(HttpRequest.newBuilder(f.uri("/echo")).header("X-Forwarded-For","fake").header("X-Request-Id","spoof")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(data)).build(),HttpResponse.BodyHandlers.ofByteArray());
            equal(200,r.statusCode());check(Arrays.equals(data,r.body()),"binary body must match");equal(2,r.headers().allValues("Set-Cookie").size());
            equal("127.0.0.1",r.headers().firstValue("X-Seen-Forwarded-For").orElse(""));check(!r.headers().firstValue("X-Request-Id").orElse("").equals("spoof"),"request ID generated");
        }
    }
    private static void semantics()throws Exception{
        try(Fixture f=new Fixture(1,Map.of())){
            check(f.get("/a%2Fb?q=x%2By&blank=").body().contains("/a%2Fb?q=x%2By&blank="),"raw URI preserved");
            HttpResponse<String> head=CLIENT.send(HttpRequest.newBuilder(f.uri("/")).method("HEAD",HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
            equal(200,head.statusCode());equal("",head.body());check(Integer.parseInt(head.headers().firstValue("Content-Length").orElse("0"))>0,"HEAD representation length");
            equal(204,f.get("/empty").statusCode());HttpResponse<String> redir=f.get("/redirect");equal(302,redir.statusCode());equal("/destination",redir.headers().firstValue("Location").orElse(""));
        }
    }
    private static String raw(int port,String request)throws IOException{
        try(Socket socket=new Socket("127.0.0.1",port)){
            socket.setSoTimeout(4000);socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));socket.getOutputStream().flush();
            InputStream in=socket.getInputStream();ByteArrayOutputStream headers=new ByteArrayOutputStream();int value;
            while((value=in.read())!=-1){headers.write(value);byte[] bytes=headers.toByteArray();int n=bytes.length;
                if(n>=4&&bytes[n-4]==13&&bytes[n-3]==10&&bytes[n-2]==13&&bytes[n-1]==10)break;
                if(n>65536)throw new IOException("Oversized headers");}
            String text=headers.toString(StandardCharsets.ISO_8859_1);int length=0;
            for(String line:text.split("\r\n"))if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());
            return text+new String(in.readNBytes(length),StandardCharsets.ISO_8859_1);
        }
    }
    private static void hopHeaders()throws Exception{
        try(Fixture f=new Fixture(1,Map.of())){
            String r=raw(f.proxy.port(),"GET /echo HTTP/1.1\r\nHost: localhost\r\nConnection: close, X-Hop-Test\r\nX-Hop-Test: secret\r\n\r\n");
            check(r.toLowerCase(Locale.ROOT).contains("x-seen-hop: null"),"request hop header removed");
            check(!r.toLowerCase(Locale.ROOT).contains("x-hop-response:"),"response hop header removed");
        }
    }
    private static void requestLimit()throws Exception{
        try(Fixture f=new Fixture(1,Map.of("proxy.maxRequestBytes","16"))){
            byte[] data=new byte[32];
            for(HttpRequest.BodyPublisher pub:List.of(HttpRequest.BodyPublishers.ofByteArray(data),HttpRequest.BodyPublishers.ofInputStream(()->new ByteArrayInputStream(data)))){
                equal(413,CLIENT.send(HttpRequest.newBuilder(f.uri("/echo")).POST(pub).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            }
            equal(0,f.backends.get(0).count.get());
        }
    }
    private static void responseLimit()throws Exception{
        try(Fixture f=new Fixture(1,Map.of("proxy.maxResponseBytes","128"))){equal(502,f.get("/large").statusCode());}
    }
    private static void getRetry()throws Exception{
        try(Fixture f=new Fixture(2,Map.of("proxy.retries","1"))){f.backends.get(0).status.set(503);HttpResponse<String> r=f.get("/");equal(200,r.statusCode());check(r.body().contains("b1"),"retry must use other backend");equal(1,f.backends.get(0).count.get());equal(1,f.backends.get(1).count.get());}
    }
    private static void postNoRetry()throws Exception{
        try(Fixture f=new Fixture(2,Map.of("proxy.retries","1"))){f.backends.get(0).status.set(503);HttpResponse<String> r=CLIENT.send(HttpRequest.newBuilder(f.uri("/")).POST(HttpRequest.BodyPublishers.ofString("payment")).build(),HttpResponse.BodyHandlers.ofString());equal(503,r.statusCode());equal(1,f.backends.get(0).count.get());equal(0,f.backends.get(1).count.get());}
    }
    private static void getBodyNoRetry()throws Exception{
        try(Fixture f=new Fixture(2,Map.of("proxy.retries","1"))){f.backends.get(0).status.set(503);HttpResponse<String> r=CLIENT.send(HttpRequest.newBuilder(f.uri("/")).method("GET",HttpRequest.BodyPublishers.ofString("body")).build(),HttpResponse.BodyHandlers.ofString());equal(503,r.statusCode());equal(0,f.backends.get(1).count.get());}
    }
    private static void healthRecovery()throws Exception{
        try(Fixture f=new Fixture(1,Map.of())){
            f.backends.get(0).healthy=false;await(()->{try{return f.admin("/readyz").statusCode()==503;}catch(Exception e){return false;}},2000);equal(503,f.get("/").statusCode());
            f.backends.get(0).healthy=true;await(()->{try{return f.admin("/readyz").statusCode()==200;}catch(Exception e){return false;}},2000);equal(200,f.get("/").statusCode());
        }
    }
    private static void circuitIntegration()throws Exception{
        try(Fixture f=new Fixture(1,Map.of("circuit.failures","2","circuit.openMs","500"))){
            f.backends.get(0).status.set(503);equal(503,f.get("/").statusCode());equal(503,f.get("/").statusCode());
            equal(503,f.get("/").statusCode());equal(2,f.backends.get(0).count.get());check(f.admin("/api/status").body().contains("OPEN"),"circuit opens");
            f.backends.get(0).status.set(200);Thread.sleep(550);equal(200,f.get("/").statusCode());check(f.admin("/api/status").body().contains("CLOSED"),"probe closes circuit");
        }
    }
    private static void bodyTimeout()throws Exception{
        com.sun.net.httpserver.HttpServer upstream=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),8);
        ExecutorService executor=Executors.newFixedThreadPool(2);upstream.setExecutor(executor);
        upstream.createContext("/",e->{try{if(e.getRequestURI().getPath().equals("/health")){e.sendResponseHeaders(200,-1);return;}e.sendResponseHeaders(200,8);e.getResponseBody().write('a');e.getResponseBody().flush();Thread.sleep(1500);e.getResponseBody().write(new byte[7]);}catch(Exception ignored){}finally{e.close();}});upstream.start();
        try(Fixture f=new Fixture(1,Map.of("proxy.requestTimeoutMs","150"))){
            f.properties.setProperty("backend.b0.url","http://127.0.0.1:"+upstream.getAddress().getPort());f.save();f.proxy.reload();
            await(()->{try{return f.admin("/readyz").statusCode()==200;}catch(Exception e){return false;}},2000);
            long start=System.nanoTime();equal(504,f.get("/").statusCode());check((System.nanoTime()-start)/1e6<1000,"body deadline must bound completion");
        }finally{upstream.stop(0);executor.shutdownNow();}
    }
    private static void rateIntegration()throws Exception{
        try(Fixture f=new Fixture(1,Map.of("rate.perSecond","0.1","rate.burst","2"))){equal(200,f.get("/").statusCode());equal(200,f.get("/").statusCode());HttpResponse<String> r=f.get("/");equal(429,r.statusCode());equal("10",r.headers().firstValue("Retry-After").orElse(""));}
    }
    private static void overload()throws Exception{
        try(Fixture f=new Fixture(1,Map.of("proxy.maxConcurrent","1","proxy.maxQueued","0","proxy.requestTimeoutMs","1500"))){
            f.backends.get(0).delayMs.set(350);CompletableFuture<HttpResponse<String>> a=CLIENT.sendAsync(HttpRequest.newBuilder(f.uri("/")).GET().build(),HttpResponse.BodyHandlers.ofString());
            await(()->f.backends.get(0).count.get()==1,1000);equal(503,f.get("/").statusCode());equal(200,a.get(2,TimeUnit.SECONDS).statusCode());f.backends.get(0).delayMs.set(0);equal(200,f.get("/").statusCode());
        }
    }
    private static void queueTimeout()throws Exception{
        try(Fixture f=new Fixture(1,Map.of("proxy.maxConcurrent","1","proxy.maxQueued","1","proxy.queueWaitMs","50","proxy.requestTimeoutMs","1500"))){
            f.backends.get(0).delayMs.set(300);CompletableFuture<HttpResponse<String>> a=CLIENT.sendAsync(HttpRequest.newBuilder(f.uri("/")).GET().build(),HttpResponse.BodyHandlers.ofString());
            await(()->f.backends.get(0).count.get()==1,1000);HttpResponse<String> r=f.get("/");equal(503,r.statusCode());check(r.body().contains("queue deadline"),"queue deadline message");equal(200,a.get(2,TimeUnit.SECONDS).statusCode());
        }
    }
    private static void reload()throws Exception{
        try(Fixture f=new Fixture(2,Map.of())){
            equal(200,f.get("/").statusCode());f.properties.setProperty("balance.algorithm","least_connections");f.save();equal(200,f.adminPost("/api/reload",true).statusCode());
            String before=f.admin("/api/status").body();check(before.contains("least_connections")&&before.contains("\"attempts\":1"),"existing counters preserved");
            f.properties.setProperty("balance.algorithm","typo");f.save();equal(400,f.adminPost("/api/reload",true).statusCode());check(f.admin("/api/status").body().contains("least_connections"),"invalid reload atomic");equal(200,f.get("/").statusCode());
            f.properties.setProperty("balance.algorithm","round_robin");f.properties.setProperty("proxy.maxConcurrent","1");f.save();equal(400,f.adminPost("/api/reload",true).statusCode());
        }
    }
    private static void reloadNewBackend()throws Exception{
        try(Fixture f=new Fixture(1,Map.of());DemoBackend other=new DemoBackend("127.0.0.1",0,"new")){
            f.properties.setProperty("backends","b0,new");f.properties.setProperty("backend.new.url","http://127.0.0.1:"+other.port());f.save();f.proxy.reload();
            await(()->{try{return f.admin("/api/status").body().split("\"healthy\":true",-1).length==3;}catch(Exception e){return false;}},2000);
            Set<String> bodies=new HashSet<>();for(int i=0;i<4;i++)bodies.add(f.get("/").body());equal(2,bodies.size());
        }
    }
    private static void adminAuth()throws Exception{
        try(Fixture f=new Fixture(1,Map.of())){equal(401,f.adminPost("/api/reload",false).statusCode());equal(200,f.adminPost("/api/reload",true).statusCode());equal(404,f.adminPost("/api/demo",true).statusCode());}
    }
    private static void unsupported()throws Exception{
        try(Fixture f=new Fixture(1,Map.of())){
            for(String method:List.of("TRACE","CONNECT")){
                String r=raw(f.proxy.port(),method+" / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");check(r.contains("501"),method+" rejected");
            }
            check(raw(f.proxy.port(),"GET / HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: close, Upgrade\r\n\r\n").contains("501"),"upgrade rejected");
            check(raw(f.proxy.port(),"GET http://example.com/ HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n").contains("400"),"absolute form rejected");
        }
    }
    private static void observability()throws Exception{
        try(Fixture f=new Fixture(1,Map.of())){
            f.get("/");String metrics=f.admin("/metrics").body();check(metrics.contains("switchyard_requests_total 1\n"),"request counter");
            check(metrics.contains("switchyard_request_duration_seconds_bucket{le=\"+Inf\"} 1"),"histogram count");
            check(f.admin("/").body().contains("Backend fleet"),"dashboard loaded");equal(200,f.admin("/healthz").statusCode());
        }
    }
    private static void gracefulShutdown()throws Exception{
        try(Fixture f=new Fixture(1,Map.of("shutdown.seconds","2","proxy.requestTimeoutMs","1500"))){
            f.backends.get(0).delayMs.set(250);CompletableFuture<HttpResponse<String>> request=CLIENT.sendAsync(HttpRequest.newBuilder(f.uri("/")).GET().build(),HttpResponse.BodyHandlers.ofString());
            await(()->f.backends.get(0).count.get()==1,1000);f.proxy.close();equal(200,request.get(2,TimeUnit.SECONDS).statusCode());
        }
    }
    private static void await(BooleanSupplier condition,int timeoutMs)throws InterruptedException{
        long end=System.nanoTime()+timeoutMs*1_000_000L;
        while(!condition.getAsBoolean()){if(System.nanoTime()>end)throw new AssertionError("Condition timed out after "+timeoutMs+"ms");Thread.sleep(10);}
    }
}
