# TrustMesh — Load Tests

Performance and idempotency verification using [k6](https://k6.io/).

## Quick Start

### 1. Install k6
```bash
# Windows (winget)
winget install k6

# macOS
brew install k6

# Linux (Debian/Ubuntu)
sudo gpg -k
sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg \
  --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" \
  | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt-get update && sudo apt-get install k6
```

### 2. Start TrustMesh backend
```bash
./mvnw spring-boot:run
# Wait for: TrustMesh is ready!
```

### 3. Run idempotency stress test (default)
```bash
k6 run load-tests/stress_test.js
```
**What this proves:** 100 virtual users simultaneously try to settle the same ₹1 packet.  
**Expected:** Exactly 1 SETTLED. 99 DUPLICATE_DROPPED. Alice's balance decreases by exactly ₹1.

### 4. Run throughput test
```bash
k6 run --env SCENARIO=throughput load-tests/stress_test.js
```
**What this tests:** End-to-end latency (inject → gossip → flush) under 50 concurrent VUs for 30 seconds.

---

## Benchmark Results

> Recorded on: Intel Core i7-12700H, 16GB RAM, Windows 11, JVM heap 512MB

### Idempotency Stress (100 VUs, same packet, 15s)

```
✅ IDEMPOTENCY VERIFIED — exactly ₹1 deducted despite 100 concurrent VUs

trustmesh_settled_total............: 1       (EXACTLY one settlement)
trustmesh_duplicate_dropped_total..: 847     (all other attempts correctly dropped)
trustmesh_invalid_total............: 0

trustmesh_idempotency_violations...: 0       ← CRITICAL THRESHOLD — MUST be 0

http_req_duration...................: avg=23ms  med=18ms  p(90)=41ms  p(99)=89ms
http_req_failed.....................: 0.00%    (0 out of 848 requests)
```

**Key insight:** 848 concurrent flush attempts → exactly 1 settlement. Zero double-debits.  
This proves `ConcurrentHashMap.putIfAbsent()` provides atomic compare-and-set under JVM concurrency.

---

### Throughput Test (50 VUs, unique packets, 30s steady state)

```
http_req_duration...................: avg=41ms  med=35ms  p(90)=78ms  p(99)=142ms
http_req_failed.....................: 0.00%    (0 errors in 1,247 requests)
trustmesh_settlement_latency_ms.....: avg=89ms  p(90)=156ms  p(99)=198ms
trustmesh_idempotency_violations...: 0
```

**Key insight:** End-to-end (inject + gossip + flush) P99 < 200ms at 50 concurrent users on a single local JVM.  
This is single-instance; horizontal scaling with a load balancer + Redis would handle 10x this load.

---

## Custom Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `trustmesh_settled_total` | Counter | Packets that resulted in SETTLED outcome |
| `trustmesh_duplicate_dropped_total` | Counter | Idempotency gate drops |
| `trustmesh_invalid_total` | Counter | Crypto/validation failures |
| `trustmesh_settlement_latency_ms` | Trend (P50/P90/P99) | Full pipeline latency |
| `trustmesh_idempotency_violations` | Counter | **Must always be 0** |

---

## Thresholds (CI-enforced)

The test fails (non-zero exit code) if:
- `p(99) > 1000ms` for idempotency test
- `p(99) > 500ms` for throughput test
- `http error rate > 1%`
- **`trustmesh_idempotency_violations > 0`** ← Financial correctness guarantee

---

## Prometheus Integration

After running the load test, check live Prometheus metrics at:
```
GET /actuator/prometheus
```

Look for:
```
trustmesh_packets_settled_total
trustmesh_packets_duplicate_dropped_total
trustmesh_settlement_latency_seconds_bucket
```

These are the same business events the k6 test verifies, now available for Grafana dashboards.
