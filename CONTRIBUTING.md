# Contributing to TrustMesh

Thank you for considering contributing to TrustMesh! 🎉

---

## 📋 Before You Start

Check [Issues](../../issues) to see if your bug/feature is already tracked.  
If not, [open a new issue](../../issues/new) before writing code.

---

## 🔀 Branching & Commits

```bash
# Fork and clone
git clone https://github.com/<your-username>/TrustMesh.git
cd TrustMesh

# Create a descriptive branch
git checkout -b feat/redis-idempotency
git checkout -b fix/circuit-breaker-timeout
git checkout -b docs/add-grafana-guide
```

### Conventional Commit Format
```
feat(scope):    new feature
fix(scope):     bug fix
docs(scope):    documentation
test(scope):    tests only
refactor(scope): no behaviour change
chore(scope):   build / config
```

Examples:
```
feat(resilience): add Resilience4j Rate Limiter on bridge/ingest
fix(jwt): handle null deviceId in registerBridgeNode
docs(adr): add ADR-006 for RS256 vs HS256 JWT
test(concurrency): add 50-VU gossip round stress test
```

---

## 🛠️ Implementation Guidelines

### Payments & Idempotency
- **All payment-touching endpoints MUST go through `IdempotencyService`**
- Never call `SettlementService.settle()` directly without the idempotency gate
- New idempotency implementations must implement the `IdempotencyService` interface (DIP)

### Security
- **Never log PII** — no VPAs, amounts, or PINs in log output
- Always use `log.info/warn/error` — never `System.out.println`
- Any new `@RestController` endpoint must be reviewed for authentication needs
- New JWT claims must be added to `JwtService.issueToken()` and documented

### Resilience
- New external service calls (DB, cache, APIs) should be wrapped with `@CircuitBreaker`
- Use `@Retry` only for transient errors — never for business rule failures
- Document fallback methods with clear Javadoc explaining the sentinel pattern

### Observability
- New business outcomes must get a corresponding Micrometer counter in `MeshMetricsService`
- Emit a corresponding `MeshEventPublisher` event for any user-visible mesh action
- Check that new metrics appear at `/actuator/prometheus` before submitting PR

### Testing
- **All code must have corresponding tests**
- Minimum targets: **unit test** for service logic + **integration test** for new endpoints
- Concurrency tests must use `CountDownLatch` or `CyclicBarrier` (not just threads)
- JWT-protected endpoints in tests must include a real Bearer token (use `JwtService.issueToken()`)
- k6 scripts should be updated if a new ingestion path is added

---

## ✅ PR Checklist

Before submitting a Pull Request:

- [ ] All existing 31 tests pass (`./mvnw test`)
- [ ] New feature has unit test(s)
- [ ] New endpoint has integration test(s)
- [ ] No PII in logs
- [ ] New metrics added to `MeshMetricsService` (if applicable)
- [ ] New WebSocket events added to `MeshEventPublisher` (if applicable)
- [ ] Javadoc on all public methods
- [ ] ADR added for significant architectural decisions (`docs/adr/`)
- [ ] `CHANGELOG.md` updated under `[Unreleased]`
- [ ] GitHub Actions CI passes (green badge)

---

## 🏗️ Local Development Setup

```bash
# Run (H2 in-memory, zero setup)
./mvnw spring-boot:run

# Run all 31 tests
./mvnw test

# Run specific test
./mvnw test -Dtest=JwtAuthTest

# Load test (requires k6 installed)
k6 run load-tests/stress_test.js

# Prometheus metrics
curl http://localhost:8080/actuator/prometheus

# Register bridge node
curl -X POST http://localhost:8080/api/bridge/register \
  -H "Content-Type: application/json" \
  -d '{"deviceId":"dev-bridge-01"}'
```

---

## 📚 Key Architecture Points to Understand First

1. **`BridgeIngestionService`** — the main pipeline orchestrator (idempotency → decrypt → freshness → settle)
2. **`SettlementService`** — `@Retry` + `@CircuitBreaker` + `@Transactional` + `@Version`
3. **`JwtAuthFilter`** — only protects `/api/bridge/ingest`, all demo endpoints are open
4. **`MeshEventPublisher`** — every outcome publishes a STOMP event (non-fatal on failure)
5. **`MeshMetricsService`** — all Micrometer counters + settlement latency timer

Read the ADRs in [`docs/adr/`](./docs/adr/) before proposing alternative approaches — they document why specific decisions were made and what was rejected.

---

## 💬 Code of Conduct

- Be respectful and constructive in reviews
- Focus feedback on the code, not the person
- Welcome contributors of all experience levels
