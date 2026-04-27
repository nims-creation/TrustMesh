# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

