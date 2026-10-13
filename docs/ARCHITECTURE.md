# Architecture

## Request path

1. The JDK server parses a request on the data listener. Switchyard generates a request ID and rejects unsupported target/method/upgrade forms.
2. A token bucket uses the actual socket peer IP. Forwarding headers cannot bypass this check.
3. A capacity permit bounds active plus waiting requests. A second permit limits active work. Requests exceeding capacity or the queue deadline get 503.
4. A request body is read into a bounded buffer. Known oversized Content-Length values are rejected early; unknown-length/chunked bodies are bounded during reading.
5. The request takes a snapshot of the current config and backend pool. Selection and reservation are synchronized in the pool. Network operations run outside those locks.
6. A circuit ticket records the circuit generation and whether it owns the single half-open probe. Stale in-flight completions cannot undo a newer failure transition.
7. Request headers are filtered, origin and forwarding metadata are rebuilt, and HttpClient sends the request. Response-body accumulation is capped by a custom BodySubscriber. A future deadline covers the complete response.
8. For an eligible bodyless GET/HEAD, a transport failure/timeout or 502/503/504 may lead to an additional attempt at a different backend. Other requests are never application-retried.
9. Response headers are filtered and repeated fields such as Set-Cookie remain separate. The buffered payload is written, admission permits are released, and counters/logs are updated.

## Routing algorithms

Round-robin rotates through eligible backends. Smooth weighted round-robin adds each eligible backend's weight to its running score, chooses the largest score, and subtracts the total eligible weight from the winner. Scores for ineligible backends reset to zero so an unavailable backend does not accumulate a large catch-up burst. Least-connections picks the fewest active upstream requests; ties rotate.

All selection is O(number of backends), capped at 32. One short selection lock is easier to reason about than a lock-free implementation here. Per-backend state has its own monitor. The lock order is pool then backend; completions lock only the backend, preventing a reverse-order lock cycle.

## Health versus circuits

Health checks answer whether a readiness endpoint is responding successfully. Circuits answer whether real requests have been failing. These are intentionally independent. An application may return 200 from `/health` but 503 for real work; the circuit isolates that failure. Health success never force-closes an open circuit.

New origins start unhealthy. Consecutive successful probes make them eligible. After enough request failures a circuit opens and starts its cooldown. Once cooldown expires, one eligible real request transitions to HALF_OPEN. Probe success closes it; failure restarts the cooldown. Other requests skip it during HALF_OPEN.

## Executors and backpressure

- Data listener: fixed worker count (`maxConcurrent + maxQueued + 4`), 64-entry executor queue, CallerRunsPolicy as a final dispatcher backpressure mechanism.
- Active admission: `maxConcurrent` permits.
- Waiting admission: up to `maxQueued` requests and `queueWaitMs` deadline.
- Admin listener: separate four-thread executor and 16-entry queue.
- Outbound client: fixed pool sized between 4 and 32. Application outbound submissions are bounded by admitted requests plus health probes; JDK internal scheduling is not a hand-written bounded network event loop.
- Health scheduling: one periodic scheduler, at most one outstanding probe per backend, shared async client.

The service is not described as fully nonblocking: handlers wait for upstream futures and body transfer. This is a deliberate Java 17 implementation choice, not an NIO event-loop implementation.

## Reload

Load -> parse -> validate all fields -> reject static-policy changes -> construct new pool -> atomically publish. IDs/origins that match reuse Backend objects (state and counters). Changed origins receive fresh unhealthy state. Existing requests retain their snapshot. Circuit thresholds, limits and timers require restart to avoid ambiguous policy/state transitions. Rate-limiter state is preserved because rate settings are not reloadable.

## Error mapping

| Status | Meaning |
| --- | --- |
| 400 | Invalid request target or invalid config reload |
| 401 | Missing/incorrect admin bearer token |
| 413 | Request body over configured cap |
| 429 | Per-peer rate limit or client-tracking saturation |
| 501 | Unsupported CONNECT/TRACE/upgrade |
| 502 | Upstream connection/protocol failure or oversized response |
| 503 | No eligible backend, admission overload, queue expiration or shutdown |
| 504 | Upstream attempt deadline |

Upstream status codes are preserved when relaying a completed response. Internal access logs can use 499 when the downstream connection disappears before headers were sent; 499 is not sent as a proxy response.

## Ownership of functionality

JDK: HTTP framing/parser, TCP connections, outbound TLS and connection reuse.

Switchyard: routing policies, eligibility/state transitions, admission/rate limits, retries, header policy, config lifecycle, observability, admin interface and demo/test harness.
