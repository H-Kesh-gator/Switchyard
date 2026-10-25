# Switchyard

**A Java HTTP reverse proxy and load balancer with failure recovery you can demonstrate.**

Route requests to a configurable backend fleet, isolate unhealthy services, limit overload, and inspect live state through a local dashboard. The project uses Java 17's HTTP server and client for HTTP parsing, sockets and connection reuse. Balancing, admission, health checks, circuit breakers, rate limiting, configuration reload and metrics are implemented here.

This is an educational implementation for controlled workloads, not a drop-in NGINX/Envoy replacement. It supports a documented HTTP/1.1 subset. See [limitations](#limits-and-trade-offs) before deploying it.

## Quick start: local demo

Requirements: **JDK 17 or newer**, Bash on Linux/macOS/WSL. No Maven, database, API key, cloud account or external Java library is needed.

```bash
java -version
bash scripts/test.sh
bash scripts/demo.sh
```

Open **http://127.0.0.1:9090**. The demo prints a random admin token; paste it into the dashboard to enable controls. All processes run locally: proxy on `8080`, admin on `9090`, backends on `9001`, `9002`, `9003`. Initial readiness normally takes about a second with the default two-success health threshold. Requests before readiness get `503`.

```bash
curl -i http://127.0.0.1:8080/
curl -s http://127.0.0.1:9090/api/status
curl -s http://127.0.0.1:9090/metrics
```

Stop with Ctrl+C. Edit ports in `config/local.properties` if they are occupied. The demo launches all three fixtures in one JVM; the Docker setup uses independent containers.

The separately supplied project archive includes a precompiled JAR in `dist/`. For that archive, run `java --add-modules jdk.httpserver -cp dist/switchyard.jar dev.switchyard.DemoMain config/local.properties`. Git clones and GitHub source ZIPs use the build commands above; compiled files are excluded from version control.

**Windows PowerShell:**

```powershell
powershell -ExecutionPolicy Bypass -File scripts/build.ps1
java --add-modules jdk.httpserver -cp build/classes dev.switchyard.DemoMain config/local.properties
```

The PowerShell build script is supplied; validation in this delivery was on Linux. Use WSL for the provided Bash test runner.

## Features

| Area | Implemented behaviour |
| --- | --- |
| Routing | Round-robin, smooth weighted round-robin, least active upstream requests with rotating ties |
| Health | Periodic, bounded probes; configurable consecutive success/failure thresholds; initially unready |
| Circuit breaker | Consecutive request failures; OPEN cooldown; one HALF_OPEN request probe; stale completions cannot close a newer circuit |
| Retries | At most configured additional attempts, each to a different eligible backend; only bodyless GET/HEAD; transport failures, timeouts and HTTP 502/503/504 |
| Request safety | Other methods and GETs with bodies never receive application-level automatic retries |
| Rate limiting | Per socket-peer IP token buckets with bounded client tracking; `429` and `Retry-After` |
| Overload | Bounded active work and admission waiting; queue deadline; `503` rejection; bounded ingress executor |
| HTTP | Raw encoded path/query, binary bodies, repeated response headers, HEAD/204/304 handling, upstream redirect passthrough |
| Headers | Removes hop-by-hop and Connection-nominated fields; replaces client-supplied forwarding metadata and request IDs |
| Observability | JSON access logs, request IDs, backend response identity, Prometheus counters/gauges/latency histogram, dashboard |
| Reload | Authenticated atomic reload of algorithm and backend IDs/origins/weights; preserves unchanged backend counters and circuit state |
| Shutdown | Stop accepting, allow in-flight exchanges a configurable grace period, then cancel outstanding requests |
| Reproducibility | Dependency-free tests, demo backends, Docker Compose, CI definition and benchmark script |

## Five-minute demo

1. Start the local demo and paste its token into the dashboard.
2. Send ten requests; observe `X-Switchyard-Backend` and balanced attempt counts.
3. Press **Unhealthy** for alpha. After health probes fail, new requests skip it. Press **Recover** and watch it return after successful probes.
4. Press **503 errors** for bravo. Its `/health` still passes. Send more traffic; request failures open its circuit. After Recover and cooldown, one real request probes it before normal routing resumes.
5. Press **Slow** to inject a three-second backend delay. The default two-second upstream deadline expires and eligible GET requests can retry another backend. Restore it with Recover.
6. Change `balance.algorithm` to `weighted_round_robin` in `config/local.properties`, click **Reload configuration**, and run 50 requests. With all backends healthy and weights 3:1:1, attempt distribution is 30:10:10.

```bash
for i in $(seq 1 10); do
  curl -s http://127.0.0.1:8080/
  printf '\n'
done
```

## Run against your own services

Configure each backend origin and make it serve `health.path` with a 2xx status when ready. Origins cannot include credentials, a base path, query or fragment. Targets retain their incoming path and query. Incoming Host is replaced with the selected backend authority; original Host is conveyed as `X-Forwarded-Host`.

```bash
# Optional for local read-only admin; required for reload and remote admin access.
export SWITCHYARD_ADMIN_TOKEN='replace-with-a-long-random-secret'
bash scripts/run.sh config/local.properties
```

The standalone process does not start fixtures. Start demo fixtures independently if desired:

```bash
java --add-modules jdk.httpserver -cp build/classes dev.switchyard.DemoBackend 9001 alpha
```

### Admin routes

| Method/path | Purpose |
| --- | --- |
| `GET /` | Static dashboard shell; no operational data embedded |
| `GET /healthz` | Process liveness |
| `GET /readyz` | At least one eligible backend and not draining |
| `GET /api/status` | Current fleet, algorithm, counters and circuit states |
| `GET /metrics` | Prometheus text format |
| `POST /api/reload` | Validate and atomically load dynamic config |
| `POST /api/demo` | DemoMain only; URL-encoded `id` and `mode` (`healthy`, `slow`, `errors`, `unhealthy`) |

Loopback read access is allowed without a token. Remote reads of operational endpoints and all mutations require `Authorization: Bearer ...`. A non-loopback admin bind requires a token of at least 16 characters at startup. No CORS access is enabled. Use a trusted local environment; admin uses HTTP, not TLS.

```bash
curl -X POST http://127.0.0.1:9090/api/reload \
  -H "Authorization: Bearer $SWITCHYARD_ADMIN_TOKEN"
```

Only algorithm and backend definitions are reloadable. Changing listener/resource/timeout/rate/health policy requires restarting. Removing a backend requires also removing its now-unused `backend.ID.*` properties. In-flight requests finish against their existing config snapshot. New origins must pass health checks; unchanged origins preserve state. Invalid reloads leave the running configuration intact.

## Configuration

`config/local.properties` lists defaults. Algorithms: `round_robin`, `weighted_round_robin`, `least_connections`. Weights affect only weighted round-robin. A value of `0` for a listener port requests an ephemeral port (used by tests).

| Settings | Meaning |
| --- | --- |
| `proxy.maxConcurrent` / `proxy.maxQueued` | Maximum admitted active and waiting request counts |
| `proxy.queueWaitMs` | Maximum admission wait |
| `proxy.connectTimeoutMs` | Upstream connection deadline |
| `proxy.requestTimeoutMs` | Deadline for one complete upstream attempt, including its response body |
| `proxy.maxRequestBytes` / `proxy.maxResponseBytes` | Bounded buffered body sizes |
| `proxy.retries` | Extra attempts (0–3); never retry an already-tried origin ID |
| `health.intervalMs` / `health.timeoutMs` | Probe schedule and full probe deadline |
| `health.failures` / `health.successes` | Consecutive probe thresholds |
| `circuit.failures` / `circuit.openMs` | Consecutive upstream-failure threshold and cooldown |
| `rate.perSecond` / `rate.burst` | Per-peer sustained rate and burst tokens; rate 0 disables |
| `rate.maxClients` | Maximum token buckets; new identities are rejected if no fully replenished idle bucket can be evicted |
| `shutdown.seconds` | Grace period for accepted exchanges |

Queue wait, client upload, upstream attempts and client download are separate phases. `requestTimeoutMs` is **per attempt**, not a global end-to-end deadline. The application uses JDK HTTP-server coarse 15-second read/write time limits, which are implementation-specific and not precise per-byte deadlines.

## Docker

```bash
export SWITCHYARD_ADMIN_TOKEN='replace-with-a-long-random-secret'
docker compose up --build
```

Proxy and admin published ports bind to host loopback only. Open the dashboard and supply the environment token. In Docker mode use `docker compose stop alpha` / `docker compose start alpha` for actual process failover; the dashboard's fixture controls are deliberately absent. Run `docker compose down` when finished. Image pulls require network access. Validate Docker execution locally.

## Tests and benchmarks

```bash
bash scripts/test.sh
python3 scripts/benchmark.py --url http://127.0.0.1:8080/ --requests 300 --concurrency 8
```

Disable the rate limiter (`rate.perSecond=0`, restart) for a throughput measurement. Otherwise 429s are expected above the configured rate. Report status counts alongside throughput. The benchmark uses persistent connections and excludes warmup, but is a small closed-loop smoke benchmark with coordinated-omission limitations. It does not establish maximum capacity, production SLOs or a fair comparison with NGINX. See `docs/VALIDATION.md` for the actual validation performed and `docs/benchmark-results.json` for the recorded run.

## Limits and trade-offs

- **Not a from-scratch HTTP parser.** JDK `HttpServer` handles inbound HTTP and `HttpClient` handles outbound connections. The project implements the proxy policies and orchestration.
- HTTP/1.1 inbound; no CONNECT tunnels, WebSocket upgrades, HTTP/2 frontend, TLS termination, gRPC or streaming/SSE. HTTPS backend origins use normal JDK certificate verification. TRACE is rejected.
- Request/response bodies are buffered with limits. This simplifies retries, enforces response deadlines and prevents forwarding a partial failed response, at the cost of latency and memory. Account for concurrent body buffers and temporary copies when choosing limits.
- Fixed-origin reverse proxy. Configure only trusted backend origins. The local self-reference check is not comprehensive loop detection through DNS, NAT or other proxies.
- `least_connections` counts **active upstream requests**, not TCP sockets. JDK connection pooling is internal and not a custom pool implemented by this project.
- `HttpServer` has limitations under hostile connection floods/slow clients. Admission is bounded; the bounded ingress executor applies dispatcher backpressure under deeper saturation. Not every transport-level rejection appears as a clean 503 or in application metrics.
- Health probes are limited to 4 KiB bodies; use a small health endpoint. They can report unhealthy during backend overload. Application 5xx and transport failures count toward circuit opening; normal 4xx responses do not.
- Rate limits are local to one proxy process. Peers behind NAT share a bucket. Forwarded headers are deliberately not trusted; deployment behind another proxy requires an explicit trusted-proxy design.
- There is no transparent Location/cookie domain rewriting, HTTP caching, upstream authentication management or automatic handling of request/response trailers.
- Automatic retries have no exactly-once guarantee. They assume GET/HEAD endpoints obey safe-method semantics. JDK connection recovery is separate from the documented application retry loop.
- Java 17's HttpClient has no explicit close method; this implementation cancels pending futures and stops its executors. Use one long-lived proxy per JVM.
- JSON access logs omit request bodies, headers and query strings. Logging is synchronous stdout and can affect latency under slow log collection.
- Dashboard/API counters are approximate concurrent snapshots. Metrics expose a fixed histogram with no URI/client-IP labels. Removed-backend time series disappear on reload.

## Read the code

See [ARCHITECTURE.md](docs/ARCHITECTURE.md), [INTERVIEW_GUIDE.md](docs/INTERVIEW_GUIDE.md), and [DEVELOPMENT.md](docs/DEVELOPMENT.md).

## References and provenance

- Starting challenge: https://codingchallenges.fyi/challenges/challenge-load-balancer/
- HTTP semantics and connection-specific fields: https://www.rfc-editor.org/rfc/rfc9110.html
- JDK server: https://docs.oracle.com/en/java/javase/17/docs/api/jdk.httpserver/com/sun/net/httpserver/HttpServer.html
- JDK client: https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpClient.html

This implementation was produced with AI assistance. The linked challenge is a learning reference; no source was copied from the example Azure repository. Before presenting it, reproduce the tests, run the failure demo, review the trade-offs and make changes you can explain. Benchmark figures must remain attached to their stated environment and workload.
