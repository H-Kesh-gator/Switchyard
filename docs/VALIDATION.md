# Validation record

Validated on 12 October 2026 in a shared Linux x86_64 container with OpenJDK 17.0.20 and Python 3.12.14. This record describes executed checks, not a production certification.

## Build and automated tests

`bash scripts/test.sh` completed with **26 passing tests**. Compilation used Java release 17 and `-Xlint:all`, with no compiler warnings. The dependency-free runner uses actual loopback HTTP servers for integration checks and deterministic algorithm/state tests. See [test-results.txt](test-results.txt) for the named checks.

Coverage includes all three routing algorithms, active-request reservations, health recovery, circuit generations and single half-open probes, bounded rate-limit tracking, binary payloads, repeated cookies, forwarding and hop-by-hop headers, encoded targets, HEAD/204, redirects, request and response limits, safe-method retry restrictions, full-response deadlines, 429 responses, overload, queue expiry, atomic reload, admin authentication, rejected unsupported HTTP operations, metrics and graceful shutdown.

## Packaged demo smoke check

The actual `build/switchyard.jar` was launched with three demo services. HTTP calls verified:

- All three services become healthy after startup.
- An unauthenticated mutation returns 401.
- Authenticated Unhealthy mode removes alpha from routing after health failures; six subsequent requests succeeded through other services.
- Recover restores alpha after successful probes.
- Authenticated configuration reload changes the algorithm to smooth weighted round-robin. With weights 3:1:1, 25 successful requests distributed as alpha 15, bravo 5, charlie 5.
- The dashboard HTML and Prometheus endpoint return 200. The dashboard's inline JavaScript passes `node --check`.

The release JAR in `dist/` is copied from this tested build. These HTTP checks do not constitute a visual browser or click-interaction test.

## Recorded smoke benchmark

See [benchmark-results.json](benchmark-results.json) for the machine-readable result. The rate limiter was disabled in a separate benchmark configuration. Proxy, fixtures and Python client ran on the same shared container, with access logging enabled.

| Measurement | Recorded value |
| --- | --- |
| Measured requests | 300 |
| Concurrent client workers | 8 |
| Excluded warmup requests | 30 |
| HTTP 200 responses | 300 / 300 |
| Backend distribution | 100 / 100 / 100 |
| Elapsed time | 1.926 seconds |
| Successful requests per second | 155.76 |
| Client latency p50 | 47.882 ms |
| Client latency p95 | 81.819 ms |
| Client latency p99 | 99.841 ms |

This is a short closed-loop smoke benchmark using persistent HTTP/1.1 connections, one outstanding request per worker. Hardware allocation and neighbouring workload were uncontrolled. It neither finds maximum throughput nor establishes a production latency target. Closed-loop generation can hide delays through coordinated omission. No direct-backend baseline or comparison against another proxy was performed. Re-measure before using performance figures in a resume or comparison.

## Not executed here

- Browser visual and click-interaction checks: a browser executable could not be provisioned. Dashboard delivery and JavaScript syntax were checked as described above.
- Docker image build and Compose startup: Docker was unavailable.
- Windows PowerShell build, macOS execution and Java 21 execution.
- GitHub Actions: the workflow is supplied but the project has not been published to GitHub.
- Sustained saturation, hostile network traffic, long-running memory profiling, and internet deployment.

## Reproduce locally

From the project root:

```bash
bash scripts/test.sh
bash scripts/demo.sh
```

Then follow the five-minute demo in the README. For a smoke benchmark, use a separate configuration with `rate.perSecond=0`, restart the demo with that configuration, and run in another terminal:

```bash
python3 scripts/benchmark.py --url http://127.0.0.1:8080/ --requests 300 --concurrency 8
```

Restore the limiter for normal use. Record your Java version, workload, environment and response status counts alongside any new results.
