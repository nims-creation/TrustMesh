# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

---

## [1.1.0] - 2026-05-09

### Added
- **Account `createdAt` field:** Accounts now record the exact `Instant` they were registered; field is immutable after creation (`updatable = false`).
- **`GET /api/accounts/{vpa}` endpoint:** Look up a single account by its Virtual Payment Address; returns `404` cleanly if the VPA is not found.
- **`GET /api/stats` endpoint:** Returns a lightweight `PacketStats` snapshot — account count, transaction count, idempotency cache size, total devices, and bridge node count.
- **`PacketStats` record:** Immutable Java Record used as the response DTO for `/api/stats`. Pure Java, no Spring dependency.
- **JVM diagnostics in `/api/health`:** Health response now includes Java version, formatted uptime, available processors, free memory, and max memory — all sourced from the JVM's `RuntimeMXBean`.
- **TTL upper-bound validation (`@Max(10)`):** `MeshPacket.ttl` now rejects values above 10 at the API layer, preventing runaway gossip in test scenarios.
- **`AccountEntityTest`:** Five focused unit tests covering constructor initialisation, `createdAt` timing, `@Version` field, and balance mutation — zero Spring context, pure JUnit 5 + AssertJ.
- **`.editorconfig`:** Enforces consistent UTF-8, LF line endings, 4-space indentation, and a trailing newline across all files in the repository.

### Changed
- **`MeshSimulatorService` Javadoc:** Full `/** … */` blocks added to all public methods explaining gossip semantics, bridge node behaviour, and reset lifecycle.
- **`CHANGELOG.md`:** Restructured with an `[Unreleased]` section per Keep a Changelog convention.

---

## [1.0.0] - 2026-04-27

### Added
- **Core Engine:** Settlement service with optimistic locking and ACID transactional integrity.
- **Mesh Simulator:** In-memory BLE device gossip simulation with dynamic bridge node assignment.
- **Cryptography:** Hybrid RSA-2048/AES encryption pipeline for secure payload tunneling over untrusted nodes.
- **Idempotency:** JVM-local concurrent idempotency cache (Redis-ready via `@Profile("prod")`) to prevent double-spending from parallel packet ingestion.
- **Observability:** Centralized UI dashboard for real-time ledger viewing, log monitoring, and mesh topology visualization.
- **CI/CD Pipeline:** Fully automated GitHub Actions workflow for building, testing, and Dockerizing the application.
- **API Documentation:** Swagger/OpenAPI integration at `/swagger-ui.html`.
- **Security:** Global HSTS and HTTP security headers enforced via custom servlet filters.

### Changed
- Refined code base with Lombok `@Slf4j` for concise logging.
- Converted all `System.out` calls to proper logger levels.
- Enhanced dashboard UI with copy-to-clipboard functionality, status badges, and transaction highlighting.

### Fixed
- Fixed raw type warnings in concurrent integration tests.
- Resolved 404 mappings returning stack traces; now cleanly mapping to structured error JSONs.

