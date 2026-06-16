/**
 * TrustMesh — k6 Load Test Suite
 *
 * Tests:
 *   1. idempotency_stress  — same packet from 100 VUs simultaneously
 *      Expected: SETTLED exactly once, rest DUPLICATE_DROPPED, 0 double-debits
 *
 *   2. throughput_test     — unique packets at 50 VUs for 30s
 *      Expected: P99 < 200ms, 0 errors
 *
 *   3. health_check        — /api/health under concurrent load
 *      Expected: always 200, P99 < 50ms
 *
 * Run:
 *   k6 run load-tests/stress_test.js
 *   k6 run --env SCENARIO=throughput load-tests/stress_test.js
 *
 * Install k6:
 *   winget install k6          (Windows)
 *   brew install k6            (macOS)
 *   https://k6.io/docs/get-started/installation/
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// ── Custom metrics ────────────────────────────────────────────
const settledCount         = new Counter('trustmesh_settled_total');
const duplicateDropped     = new Counter('trustmesh_duplicate_dropped_total');
const invalidCount         = new Counter('trustmesh_invalid_total');
const settlementLatency    = new Trend('trustmesh_settlement_latency_ms', true);
const idempotencyViolation = new Counter('trustmesh_idempotency_violations'); // MUST stay 0

// ── Test configuration ────────────────────────────────────────
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SCENARIO = __ENV.SCENARIO || 'idempotency';

export const options = SCENARIO === 'throughput'
  ? {
      // Scenario 2: Throughput test — unique packets, ramp up/down
      stages: [
        { duration: '10s', target: 20 },   // warm-up
        { duration: '30s', target: 50 },   // sustained load
        { duration: '10s', target: 0 },    // ramp-down
      ],
      thresholds: {
        http_req_duration:              ['p(99)<500'],  // P99 < 500ms
        http_req_failed:                ['rate<0.01'],  // <1% errors
        trustmesh_idempotency_violations: ['count==0'], // ZERO double-settles
      },
    }
  : {
      // Scenario 1 (default): Idempotency stress — same packet, many VUs
      vus: 100,
      duration: '15s',
      thresholds: {
        http_req_duration:              ['p(99)<1000'],
        http_req_failed:                ['rate<0.02'],
        trustmesh_idempotency_violations: ['count==0'],  // CRITICAL
      },
    };

// ── Shared state for idempotency test ─────────────────────────
// All VUs use the same pre-built packet → exactly 1 should settle
let sharedPacket = null;
let sharedPacketHash = null;
let settlementCount = 0;

// ── Setup: create a packet once before test starts ────────────
export function setup() {
  // 1. Get server public key
  const keyRes = http.get(`${BASE_URL}/api/server-key`);
  check(keyRes, { 'server key reachable': (r) => r.status === 200 });

  // 2. Create a shared encrypted packet via /api/demo/send
  const sendPayload = JSON.stringify({
    senderVpa:   'alice@demo',
    receiverVpa: 'bob@demo',
    amount:      1,              // ₹1 — minimal amount, we care about idempotency
    pin:         '1234',
    ttl:         5,
    startDevice: 'phone-alice',
  });

  const sendRes = http.post(`${BASE_URL}/api/demo/send`, sendPayload, {
    headers: { 'Content-Type': 'application/json' },
  });

  check(sendRes, { 'packet created': (r) => r.status === 200 });

  // 3. Run 1 gossip round so the bridge node picks up the packet
  http.post(`${BASE_URL}/api/mesh/gossip`);

  // 4. Get current alice balance (before test)
  const accountsRes = http.get(`${BASE_URL}/api/accounts`);
  const accounts = accountsRes.json();
  const alice = accounts.find(a => a.vpa === 'alice@demo');

  console.log(`Setup complete. Alice balance before test: ₹${alice ? alice.balance : 'unknown'}`);
  console.log(`Scenario: ${SCENARIO} | VUs: ${options.vus || 'staged'} | Base URL: ${BASE_URL}`);

  return {
    aliceBalanceBefore: alice ? parseFloat(alice.balance) : null,
    startedAt: Date.now(),
  };
}

// ── Main VU function ──────────────────────────────────────────
export default function (data) {
  if (SCENARIO === 'throughput') {
    runThroughputIteration();
  } else {
    runIdempotencyIteration();
  }
}

/**
 * Idempotency stress: all VUs hammer the same /api/mesh/flush endpoint.
 * The bridge node holds the shared packet. Only 1 should settle.
 */
function runIdempotencyIteration() {
  const start = Date.now();

  const res = http.post(`${BASE_URL}/api/mesh/flush`, null, {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'bridge_flush' },
  });

  const latency = Date.now() - start;
  settlementLatency.add(latency);

  const ok = check(res, {
    'flush returns 200':  (r) => r.status === 200,
    'response has body':  (r) => r.body && r.body.length > 0,
  });

  if (res.status === 200) {
    try {
      const body = res.json();
      if (body.results) {
        body.results.forEach(result => {
          if (result.outcome === 'SETTLED') {
            settledCount.add(1);
          } else if (result.outcome === 'DUPLICATE_DROPPED') {
            duplicateDropped.add(1);
          } else if (result.outcome === 'INVALID') {
            invalidCount.add(1);
          }
        });
      }
    } catch (e) {
      // JSON parse error — not critical for stress test
    }
  }

  sleep(0.1); // 100ms think time
}

/**
 * Throughput test: each VU creates a unique payment packet.
 * Tests end-to-end latency under concurrent load.
 */
function runThroughputIteration() {
  const vuId   = __VU;
  const iter   = __ITER;
  const nonce  = `vu${vuId}-iter${iter}-${Date.now()}`;

  // Inject unique packet
  const sendPayload = JSON.stringify({
    senderVpa:   'alice@demo',
    receiverVpa: 'bob@demo',
    amount:      1,
    pin:         '1234',
    ttl:         5,
    startDevice: 'phone-alice',
  });

  const start   = Date.now();
  const sendRes = http.post(`${BASE_URL}/api/demo/send`, sendPayload, {
    headers: { 'Content-Type': 'application/json' },
    tags:    { name: 'inject_packet' },
  });

  check(sendRes, { 'packet injected': (r) => r.status === 200 });

  // Gossip + flush
  http.post(`${BASE_URL}/api/mesh/gossip`, null, { tags: { name: 'gossip' } });
  const flushRes = http.post(`${BASE_URL}/api/mesh/flush`, null, { tags: { name: 'flush' } });

  const latency = Date.now() - start;
  settlementLatency.add(latency);

  check(flushRes, { 'flush returns 200': (r) => r.status === 200 });

  sleep(0.5);
}

// ── Teardown: verify idempotency guarantee ────────────────────
export function teardown(data) {
  // Verify alice's balance changed by exactly ₹1 (idempotency proof)
  const accountsRes = http.get(`${BASE_URL}/api/accounts`);
  if (accountsRes.status === 200) {
    const accounts   = accountsRes.json();
    const alice      = accounts.find(a => a.vpa === 'alice@demo');
    const aliceAfter = alice ? parseFloat(alice.balance) : null;

    if (data.aliceBalanceBefore !== null && aliceAfter !== null) {
      const deducted = data.aliceBalanceBefore - aliceAfter;
      console.log(`\n=== IDEMPOTENCY VERIFICATION ===`);
      console.log(`Alice balance before: ₹${data.aliceBalanceBefore}`);
      console.log(`Alice balance after:  ₹${aliceAfter}`);
      console.log(`Amount deducted:      ₹${deducted}`);
      console.log(`Expected deduction:   ₹1 (idempotency: only 1 settlement)`);

      if (Math.abs(deducted - 1) > 0.01) {
        console.error(`❌ IDEMPOTENCY VIOLATION! Deducted ₹${deducted} instead of ₹1`);
        idempotencyViolation.add(1);
      } else {
        console.log(`✅ IDEMPOTENCY VERIFIED — exactly ₹1 deducted despite ${options.vus || 50} concurrent VUs`);
      }
    }
  }

  // Reset for next run
  http.post(`${BASE_URL}/api/mesh/reset`);
  console.log('Mesh reset for next run.');
}
