# Changelog

All notable changes to TrustMesh are documented here.  
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).  
Versioning follows [Semantic Versioning](https://semver.org/).

---

## [2.2.0] — 2026-06-24 · "Humanized"

Complete frontend modernization — warm, trustworthy design system replacing the cold neon palette.

### 🎨 Design System Overhaul

#### Humanized Color Palette
- **Soft Indigo** (`#6C63FF`) primary — trust, warmth, replacing cold neon cyan
- **Mint Green** (`#34D399`) success — softer than electric green
- **Warm Peach** (`#FF8A65`) accent — human warmth
- **Sky Blue** (`#38BDF8`) accent — friendly information
- **Warm Amber** (`#FBBF24`) — warnings and highlights
- Softer dark background (`#0f1117`) — not pure black

#### Inline SVG Graphics
- Mesh network logo SVG in sidebar (replaces emoji icon)
- Wallet/shield header SVG on Accounts page
- Arrow transfer SVG on Demo page title
- Step connector SVGs linking demo flow cards (dashed lines + gradient dots)
- Animated pulse SVG live indicator on System Logs page
- Mesh grid pattern SVG overlay on canvas card
- Empty state illustrations for accounts and wallets

#### Premium Metric Cards
- Gradient icon containers (indigo, mint, peach, sky, amber variants)
- Rounded icon with subtle glow shadow
- Large Outfit font numbers with label below
- Decorative sparkle radial gradient accent

### ✨ Polished Components
- **Buttons**: Pill-shaped (`border-radius: 100px`), gradient fills, micro-bounce on click, hover lift
- **Cards**: `28px` radius, softer glow on hover (indigo tones)
- **Sidebar**: Refined active indicator (3px indigo left border), softer hover states
- **Toasts**: Slide-in-from-right animation with backdrop blur + scale
- **Modals**: Scale-in entrance animation + backdrop blur
- **Tables**: Warm zebra striping (subtle indigo tint on even rows)
- **Forms**: Softer focus glow (indigo instead of cyan)

### 🖌️ Canvas Visualizer Updates
- Node colors updated to match humanized palette
- Bridge nodes: Purple (`#8B5CF6`) glow instead of neon violet
- Active nodes: Soft Indigo (`#6C63FF`) instead of cyan
- Settlement burst: Mint green (`#34D399`)
- Softer connection line opacity

### 🌟 Background & Atmosphere
- Dual animated gradient orbs (indigo + mint) floating in background
- Softer radial gradient washes behind body
- SVG mesh grid pattern on canvas card for depth

---

## [2.1.0] — 2024-06-18 · "Visualizer"

Complete dashboard overhaul based on interviewer feedback:
_"Frontend too basic, everything on one page, show how packets travel visually."_

### ✨ Added

#### Animated Mesh Network Canvas
- Real-time Canvas-based network graph — phones rendered as interactive nodes
- **Purple pulsing ring** = 4G bridge node
- **Green glow** = offline phone holding a packet
- **Grey** = idle offline phone
- Animated yellow packet dot flying between nodes during gossip rounds
- Ripple wave animations on each node when gossip propagates
- Green burst animation on bridge node when payment settles
- Packet count badge rendered on each node

#### Packet Journey Tracker (sidebar)
- Step-by-step live tracker showing every stage of a packet's lifecycle:
  - `📤 Encrypted & Injected` — AES-256-GCM ciphertext ready
  - `🔄 Gossiped — N hop(s)` — with device map
  - `📡 Bridge: <node> Uploaded to backend`
  - `✅ SETTLED on Ledger!` or `🚫 REJECTED (reason)`
- Status chip updates live as each stage completes

#### 4-Tab Layout (nothing on one page anymore)
- **🗺 Mesh Visualizer** — animated canvas + journey tracker + live metrics + quick actions
- **🎬 Demo** — step-by-step numbered flow with ciphertext display + stress test + add-account
- **📜 Ledger** — transactions table + account balances + mesh devices grid
- **⚡ Activity** — real-time WebSocket event log, color-coded by event type

#### Live Metrics Panel
- Settled TXs / Packets in Mesh / Duplicates Dropped / Accounts — updates via WebSocket

### 🎨 Design Overhaul
- Dark glassmorphism theme (`#080b14` base)
- Inter + JetBrains Mono fonts (Google Fonts)
- Gradient accent buttons with hover lift + glow
- Responsive grid layout
- Status badges (green=settled, amber=circuit-open, red=rejected)
- Fixed nav with backdrop blur + WebSocket live indicator

---

## [2.0.0] — 2024-06-17 · "Production-Grade"

This release adds seven production-quality features on top of the v1.0 demo foundation.
Every feature is tested, committed individually, and explained with Architecture Decision Records.

### ✨ Added

#### Observability — Spring Actuator + Micrometer + Prometheus
- `GET /actuator/health` — liveness/readiness probe (HTTP 200/503)
- `GET /actuator/prometheus` — Prometheus scrape endpoint with custom business metrics
- `GET /actuator/info` — app metadata (name, version)
- Custom `MeshMetricsService` — 7 Micrometer counters + 1 Timer:
  - `trustmesh_packets_settled_total`
  - `trustmesh_packets_duplicate_dropped_total`
  - `trustmesh_packets_invalid_total`
  - `trustmesh_packets_rejected_total`
  - `trustmesh_packets_circuit_open_total`
  - `trustmesh_gossip_rounds_total`
  - `trustmesh_bridge_uploads_total`
  - `trustmesh_settlement_latency_seconds` (P50/P95/P99 histogram)
- `/api/health` enriched with business metrics snapshot and JVM stats

#### Architecture Decision Records (`docs/adr/`)
- [ADR-001](docs/adr/ADR-001-hybrid-encryption.md) — Why RSA-OAEP + AES-256-GCM (not pure RSA or pure AES)
- [ADR-002](docs/adr/ADR-002-aes-gcm-not-cbc.md) — Why AES-GCM (AEAD) over AES-CBC (padding oracle attacks)
- [ADR-003](docs/adr/ADR-003-optimistic-locking.md) — Why `@Version` optimistic locking over `SELECT FOR UPDATE`
- [ADR-004](docs/adr/ADR-004-idempotency-concurrenthashmap.md) — Why `ConcurrentHashMap.putIfAbsent()` (atomic CAS, not synchronized)
- [ADR-005](docs/adr/ADR-005-redis-production-idempotency.md) — Why Redis `SET NX EX` for production distributed idempotency

#### k6 Load Tests (`load-tests/`)
- `stress_test.js` — two scenarios:
  - **Idempotency stress**: 100 VUs hammer the same packet simultaneously
    → Verified: exactly 1 SETTLED, rest DUPLICATE_DROPPED, zero double-debits
  - **Throughput test**: staged ramp 0→50 VUs, unique packets, P99 < 500ms threshold
- Custom k6 metrics: settled/duplicate/invalid counters + settlement latency Trend
- `trustmesh_idempotency_violations` threshold: **MUST be 0** (financial correctness gate)
- Benchmark results documented: 848 concurrent flush attempts → 1 SETTLED

#### WebSocket Real-Time Events (STOMP over SockJS)
- `WebSocketConfig.java` — STOMP broker at `/ws` with SockJS fallback
- `MeshEventPublisher.java` — 7 event types pushed to `/topic/mesh-events`:
  - `PACKET_INJECTED`, `GOSSIP_ROUND`, `BRIDGE_UPLOAD`
  - `PACKET_SETTLED`, `PACKET_DUPLICATE`, `PACKET_INVALID`, `MESH_RESET`
- Dashboard upgraded from 3-second polling to real-time push
- Health badge updates to "Live ⚡" on WebSocket connect
- Non-fatal: WebSocket failure never blocks the payment pipeline

#### JWT Authentication for Bridge Nodes (JJWT 0.12.6)
- `POST /api/bridge/register` — issues HS256 JWT (24h expiry, role=BRIDGE_NODE)
- `JwtAuthFilter` — `OncePerRequestFilter` protecting `/api/bridge/ingest`
  - Missing/invalid token → `401 Unauthorized` with JSON hint
  - Valid token → sets `authenticatedBridgeNodeId` request attribute
- `JwtService` — `issueToken()`, `validateToken()`, `isTokenValid()`, `extractDeviceId()`
- Authenticated deviceId takes precedence over `X-Bridge-Node-Id` header
- Demo dashboard unaffected (uses `/api/mesh/flush`, not raw `/api/bridge/ingest`)

#### Resilience4j Circuit Breaker + Retry
- `@Retry("settlementRetry")` on `SettlementService.settle()`:
  - Max 3 attempts, 200ms delay
  - Retries only transient DB errors (JpaSystemException, OptimisticLockingFailureException)
  - **Does NOT retry** `InsufficientFundsException` (business rule, not transient)
- `@CircuitBreaker("settlementCB")` with `settleFallback()`:
  - Opens after 50% failure rate in 10-call sliding window
  - 10s open → half-open (3 probe calls) → closed/open
  - Fallback returns sentinel `CIRCUIT_OPEN` Transaction (not throwing)
  - Prevents DB failure storms from cascading into the ingestion pipeline
- Ordering: `@Retry` wraps `@CircuitBreaker` wraps `@Transactional`
- Circuit breaker state exposed at `/actuator/circuitbreakers`

### 🧪 Tests
- **31 tests** — all passing, 0 failures
- `JwtAuthTest` (4 tests): token round-trip, invalid input, register endpoint, 401 on missing auth
- `GlobalExceptionHandlerTest` updated: includes Bearer JWT for `/api/bridge/ingest` calls

### 📚 Dependency Stack
| Dependency | Version | Purpose |
|---|---|---|
| spring-boot-starter-actuator | 3.3.5 | Health/metrics endpoints |
| micrometer-registry-prometheus | 1.13.x | Prometheus scrape |
| spring-boot-starter-websocket | 3.3.5 | STOMP + SockJS |
| io.jsonwebtoken:jjwt-api | 0.12.6 | JWT issuance |
| io.jsonwebtoken:jjwt-impl | 0.12.6 | JWT signing (runtime) |
| io.jsonwebtoken:jjwt-jackson | 0.12.6 | JWT JSON (runtime) |
| resilience4j-spring-boot3 | 2.2.0 | Circuit breaker + Retry |
| spring-boot-starter-aop | 3.3.5 | AOP for @CircuitBreaker |

---

## [1.0.0] — 2024-01-15 · "Demo Foundation"

### ✨ Added
- Hybrid encryption pipeline: RSA-2048/OAEP + AES-256-GCM
  - `HybridCryptoService` — encrypt/decrypt/hashCiphertext
  - `ServerKeyHolder` — RSA keypair generated at JVM startup
- Bluetooth mesh simulation
  - `VirtualDevice` — in-memory packet queue per simulated phone
  - `MeshSimulatorService` — inject, gossipOnce, collectBridgeUploads, resetMesh
- Production bridge ingestion pipeline
  - `BridgeIngestionService` — idempotency gate → decrypt → freshness → settle
  - `IdempotencyService` / `LocalIdempotencyService` — `ConcurrentHashMap.putIfAbsent()`
- ACID settlement
  - `SettlementService` — `@Transactional` debit + credit + transaction record
  - `Account` — `@Version` optimistic locking on balance
- REST API — 12 endpoints (server-key, demo/send, mesh/*, bridge/*, accounts, stats)
- Swagger UI at `/swagger-ui.html`
- Spring Flyway migration — schema versioned from v1
- H2 in-memory DB for dev/test, PostgreSQL-ready for prod
- Dark-mode dashboard (vanilla HTML/CSS/JS)
- Security headers filter (X-Frame-Options, X-Content-Type-Options, CSP)
- Request logging filter with correlation IDs
- GlobalExceptionHandler — structured error JSON (400, 422, 500)
- 27 tests: concurrency, crypto, idempotency, settlement, security headers

---

## How to Run

```bash
# Clone and run (zero external dependencies)
git clone https://github.com/nims-creation/TrustMesh
cd TrustMesh
./mvnw spring-boot:run

# Open dashboard
open http://localhost:8080

# Prometheus metrics
curl http://localhost:8080/actuator/prometheus

# Register a bridge node (JWT)
curl -X POST http://localhost:8080/api/bridge/register \
  -H "Content-Type: application/json" \
  -d '{"deviceId":"my-bridge-01"}'

# Run load tests (requires k6)
k6 run load-tests/stress_test.js
```

---

## Architecture

```
Android Phone (offline)
  └── AES-256-GCM encrypted PaymentInstruction
        └── [BLE Gossip] → VirtualDevice mesh
              └── bridge node (4G internet)
                    └── POST /api/bridge/ingest  [JWT Auth]
                          └── BridgeIngestionService
                                ├── SHA-256 idempotency gate (ConcurrentHashMap)
                                ├── RSA decrypt → AES-GCM verify integrity
                                ├── Freshness check (replay attack protection)
                                └── SettlementService [@Retry + @CircuitBreaker]
                                      └── @Transactional + @Version optimistic lock
                                            └── H2 / PostgreSQL
```

**Observability layer:** Every outcome → Prometheus counter → Grafana dashboard  
**Real-time layer:** Every event → WebSocket push → live dashboard  
**Resilience layer:** Circuit breaker prevents DB failure storms
