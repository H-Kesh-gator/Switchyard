#!/usr/bin/env python3
"""Small reproducible smoke benchmark. Each worker uses a persistent HTTP/1.1 connection.
This is a closed-loop client, not a capacity/SLO benchmark. Python 3 standard library only.
"""
import argparse
import collections
import concurrent.futures
import datetime
import http.client
import json
import math
import platform
import statistics
import sys
import time
import urllib.parse

parser = argparse.ArgumentParser()
parser.add_argument('--url', default='http://127.0.0.1:8080/')
parser.add_argument('--requests', type=int, default=300)
parser.add_argument('--concurrency', type=int, default=8)
parser.add_argument('--warmup', type=int, default=30)
parser.add_argument('--output')
args = parser.parse_args()
if not (0 < args.concurrency <= args.requests and args.warmup >= 0):
    parser.error('Require requests >= concurrency > 0 and warmup >= 0')
url = urllib.parse.urlsplit(args.url)
if url.scheme not in ('http', 'https') or not url.hostname:
    parser.error('Provide an http(s) URL')
target = urllib.parse.urlunsplit(('', '', url.path or '/', url.query, ''))

def worker(count):
    samples, statuses, identities = [], collections.Counter(), collections.Counter()
    kind = http.client.HTTPSConnection if url.scheme == 'https' else http.client.HTTPConnection
    connection = kind(url.hostname, url.port, timeout=10)
    for _ in range(count):
        start = time.perf_counter()
        try:
            connection.request('GET', target)
            response = connection.getresponse()
            response.read()
            statuses[str(response.status)] += 1
            identities[response.getheader('X-Switchyard-Backend', 'direct-or-error')] += 1
        except (OSError, http.client.HTTPException):
            statuses['transport_error'] += 1
            connection.close()
            connection = kind(url.hostname, url.port, timeout=10)
        samples.append((time.perf_counter() - start) * 1000)
    connection.close()
    return samples, statuses, identities

worker(args.warmup)
start = time.perf_counter()
with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as executor:
    counts = [args.requests // args.concurrency + (i < args.requests % args.concurrency) for i in range(args.concurrency)]
    results = list(executor.map(worker, counts))
duration = time.perf_counter() - start
samples, statuses, identities = [], collections.Counter(), collections.Counter()
for values, status, identity in results:
    samples.extend(values); statuses.update(status); identities.update(identity)
samples.sort()
percentile = lambda p: round(samples[max(0, math.ceil(len(samples) * p) - 1)], 3)
report = {
    'timestamp_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'environment': platform.platform(), 'python': platform.python_version(),
    'url': args.url, 'requests': args.requests, 'concurrency': args.concurrency,
    'warmup_requests': args.warmup, 'elapsed_seconds': round(duration, 3),
    'requests_per_second': round(args.requests / duration, 2),
    'successful_requests_per_second': round(sum(v for k, v in statuses.items() if k.startswith('2')) / duration, 2),
    'latency_ms': {'p50': percentile(.50), 'p95': percentile(.95), 'p99': percentile(.99), 'max': round(max(samples), 3)},
    'statuses': dict(statuses), 'backend_counts': dict(identities),
    'methodology': 'Closed-loop, persistent HTTP/1.1, one outstanding request per Python worker; warmup excluded; access logging enabled.'
}
text = json.dumps(report, indent=2)
print(text)
if args.output:
    with open(args.output, 'w') as output:
        output.write(text + '\n')
if statuses.get('transport_error') or any(not status.startswith('2') for status in statuses):
    sys.exit('Benchmark contains failed/non-2xx requests; inspect status counts before interpreting throughput.')
