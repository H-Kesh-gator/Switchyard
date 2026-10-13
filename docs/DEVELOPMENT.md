# Development

## Layout

| File | Responsibility |
| --- | --- |
| `Config.java` | Property parsing, validation, reload policy |
| `Backend.java` | Health/circuit state and active request tickets |
| `BackendPool.java` | Routing algorithms and atomic selection/reservation |
| `RateLimiter.java` | Token bucket and bounded peer cardinality |
| `HttpSupport.java` | Header policy helpers, bounded body receiver, response helpers |
| `ProxyServer.java` | Request lifecycle, health scheduling, admin and reload |
| `Metrics.java` | Low-cardinality counters and Prometheus histogram |
| `Main.java` | Standalone process lifecycle |
| `DemoBackend.java`, `DemoMain.java` | Reproducible local services and failure controls |
| `AllTests.java` | Dependency-free test runner |

## Build

`bash scripts/build.sh` compiles with `--release 17`, copies the dashboard resource and produces `build/switchyard.jar`. The script can also use a JDK compiler module if its `javac` launcher is missing. A normal installation needs a full JDK, not a stripped JRE.

## Debug

Import the folder as a Java project and mark `src/main/java` as sources, `src/main/resources` as resources and `src/test/java` as test sources. Set the project SDK to Java 17+. Launch `dev.switchyard.DemoMain` with the repository root as working directory.

Run from terminal with a local debugger:

```bash
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005 \
  --add-modules jdk.httpserver -cp build/classes dev.switchyard.DemoMain
```

## Publish to your GitHub

Create an empty repository in your account, then from this folder:

```bash
git init
git add .
git commit -m "Build Java reverse proxy with balancing and failure recovery"
git branch -M main
git remote add origin YOUR_REPOSITORY_URL
git push -u origin main
```

Use GitHub's normal authentication flow. Never put access tokens into source code or configuration. This delivery did not create a GitHub repository or push any commits. CI is supplied but runs only after publishing.

## Benchmark discipline

Keep the rate limiter disabled only in a dedicated local benchmark configuration. Save JSON results with the workload, concurrency, Java version and hardware/environment. Warm up the JVM; compare proxy and direct-backend requests under identical conditions. Do not equate a 300-request closed-loop run with a saturation test. To study capacity, use a fixed-arrival-rate load generator and record server resource usage, queue growth and error counts.
