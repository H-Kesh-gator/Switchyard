# Interview preparation

Use these questions to verify you understand the implementation. Do not memorise the feature list without running the code.

1. **Why are retries restricted?** A timeout does not prove that the backend did nothing. Replaying POST could duplicate a payment. This proxy retries only bodyless GET/HEAD and still relies on their safe semantics.
2. **Why both health checks and circuit breakers?** A health endpoint can pass while real work fails. They measure different signals and recover through different paths.
3. **How do two threads avoid choosing the same supposedly idle backend?** Backend selection and active-count reservation share the pool monitor. Completion decrements under the backend monitor.
4. **What happens if an old request succeeds after the circuit opens?** Its generation no longer matches. The completion updates counters but cannot close the newer circuit.
5. **What does least-connections actually measure?** Active upstream requests. The JDK owns persistent TCP sockets, so the name is a conventional approximation.
6. **Why bounded queues?** An unbounded backlog can exhaust memory and create very long latency. The service caps admission and waiting time and rejects excess load.
7. **Why not trust X-Forwarded-For?** An arbitrary client could forge identities to bypass the limiter. A trusted-proxy chain needs an explicit trust policy.
8. **How does weighted round-robin avoid bursts?** Smooth scores interleave the weighted selections rather than sending every request for a high-weight backend in one contiguous block.
9. **Why buffer the response?** The proxy can enforce size and full-response timeout before committing downstream headers. It cannot support indefinite streams, and memory grows with active body sizes.
10. **Why custom response BodySubscriber?** Checking the size after BodyHandlers.ofByteArray has already allocated an oversized body would not enforce a meaningful memory bound.
11. **What headers must not be forwarded?** Connection-specific headers and any headers nominated by Connection. Content-Length is rebuilt according to the outgoing body. Repeated Set-Cookie values must not be merged into one comma-separated header.
12. **Why disable redirects on the upstream client?** A reverse proxy should return the backend's redirect to the caller, not silently follow it.
13. **How does config reload avoid a partial update?** A complete candidate validates before one atomic state swap. In-flight requests retain their prior snapshot.
14. **What is the timeout budget?** Admission wait + upload + one or more per-attempt upstream deadlines + download. It is not a single global request deadline.
15. **Is this production-ready?** It has tested proxy policies but intentionally limited HTTP support, no frontend TLS/HTTP2/streaming, process-local rate limits and JDK-server limitations under hostile connections.
16. **How did you test it?** Real loopback HTTP servers plus deterministic algorithm/state-machine tests; 503 injection, body stalls, binary payloads, retries, rate limiting, overload, reload and shutdown.
17. **What did AI do?** Explain which code was generated and which changes you reviewed, tested and made yourself. Reproduce a bug or extension you understand.

## Suggested resume content after hands-on review

Describe the implemented scope: Java HTTP reverse proxy with configurable balancing, health probes, circuit breakers, bounded admission and peer-IP rate limits. Mention the failure tests and reproducible demo. Add throughput/latency numbers only after measuring on your own machine and naming workload/concurrency in the README.

Avoid claims such as "built the HTTP protocol from scratch", "zero-loss retries", "production-grade", "distributed rate limiting", "NIO event-loop server" or "handles millions of requests". They do not describe this implementation.

## Useful next changes to own

- Add a global retry time budget with a test proving total duration stays bounded.
- Add per-backend active limits and show why a hot backend can overload despite overall proxy capacity.
- Compare the three policies with heterogeneous backend latency; document the fairness/latency trade-off.
- Replace blocking waits with a deliberate Netty architecture only after measuring a workload where it helps.
