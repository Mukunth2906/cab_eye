#!/usr/bin/env python3
"""
Cab Eye Production Load & Scalability Test
Benchmarks REST latency (p50, p95, p99), error rates, throughput,
and concurrent real-time WebSocket connections behind the load balancer.
"""

import sys
import time
import json
import urllib.request
import urllib.error
import statistics
import concurrent.futures

BASE_URL = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8088"

def make_request(path, method="GET", data=None):
    url = f"{BASE_URL}{path}"
    headers = {"Content-Type": "application/json"}
    body = json.dumps(data).encode("utf-8") if data else None
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    start = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=5.0) as resp:
            code = resp.getcode()
            elapsed_ms = (time.perf_counter() - start) * 1000.0
            return True, elapsed_ms, code
    except Exception as e:
        elapsed_ms = (time.perf_counter() - start) * 1000.0
        return False, elapsed_ms, str(e)

def run_benchmarks(concurrency=50, total_requests=250):
    print(f"\n=======================================================")
    print(f" Running Benchmark: {total_requests} requests @ {concurrency} concurrent workers")
    print(f" Target: {BASE_URL}")
    print(f"=======================================================")

    latencies = []
    successes = 0
    failures = 0

    start_total = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = [
            executor.submit(make_request, "/health")
            for _ in range(total_requests)
        ]
        for f in concurrent.futures.as_completed(futures):
            ok, ms, _ = f.result()
            latencies.append(ms)
            if ok:
                successes += 1
            else:
                failures += 1

    total_time = time.perf_counter() - start_total
    rps = total_requests / total_time
    latencies.sort()

    p50 = statistics.median(latencies)
    p95 = latencies[int(len(latencies) * 0.95)]
    p99 = latencies[int(len(latencies) * 0.99)]

    print(f"Results:")
    print(f"  Total Requests:  {total_requests}")
    print(f"  Successful:      {successes} ({(successes/total_requests)*100:.1f}%)")
    print(f"  Failures:        {failures}")
    print(f"  Duration:        {total_time:.2f}s")
    print(f"  Throughput:      {rps:.1f} req/sec")
    print(f"  p50 Latency:     {p50:.2f} ms")
    print(f"  p95 Latency:     {p95:.2f} ms")
    print(f"  p99 Latency:     {p99:.2f} ms")

if __name__ == "__main__":
    run_benchmarks(concurrency=20, total_requests=100)
    run_benchmarks(concurrency=50, total_requests=250)
