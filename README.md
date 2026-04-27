# 🌐 TrustMesh: UPI Offline Mesh System

[![Java CI with Maven](https://github.com/nims-creation/TrustMesh/actions/workflows/ci.yml/badge.svg)](https://github.com/nims-creation/TrustMesh/actions/workflows/ci.yml)
[![Version](https://img.shields.io/badge/version-1.0.0-blue.svg)](https://github.com/nims-creation/TrustMesh/releases)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.3.5-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Docker](https://img.shields.io/badge/Docker-Ready-blue.svg)](https://www.docker.com/)

**TrustMesh** is a highly resilient, offline-first digital payments engine designed to process transactions without internet connectivity. Inspired by UPI and built for rural penetration, it utilizes a simulated BLE (Bluetooth Low Energy) mesh network to securely encrypt, route, and gossip payment packets device-to-device until an internet-connected "bridge node" is found to process the transaction.

---

## 🚀 Quick Start

Ensure you have Docker and Docker Compose installed.

```bash
# 1. Clone the repository
git clone https://github.com/nims-creation/TrustMesh.git
cd TrustMesh

# 2. Start the application via Docker Compose
make docker-up
# Or manually: docker-compose up --build -d

# 3. Access the Live Dashboard
open http://localhost:8080/

# 4. Access the API Documentation (Swagger UI)
open http://localhost:8080/swagger-ui.html
```

---

## 🏛️ System Architecture

TrustMesh leverages an event-driven, hybrid cryptographic pipeline to ensure absolute data integrity over untrusted transit networks.

```mermaid
sequenceDiagram
    participant S as Sender (Offline)
    participant M as Mesh (Untrusted Phones)
    participant B as Bridge Node (4G)
    participant API as API Gateway (Spring Boot)
    participant IC as Idempotency Cache
    participant Core as Settlement Ledger

    Note over S,M: 1. Offline Payment Creation
    S->>S: Input Receiver VPA & Amount
    S->>S: Fetch Server Public Key (Cached)
    S->>S: Encrypt Payload (RSA/AES)
    
    Note over S,B: 2. Mesh Gossip (Bluetooth Simulation)
    S->>M: Broadcast Encrypted Packet
    M->>M: Device-to-Device Gossip (Hop count +1)
    M->>B: Packet reaches internet-connected device
    
    Note over B,Core: 3. Bridge Ingestion & Settlement
    B->>API: POST /api/bridge/ingest (Ciphertext)
    
    API->>IC: Check Packet Hash (SETNX)
    alt Is Duplicate Packet?
        IC-->>API: Duplicate Found
        API-->>B: 409 Conflict (Drop)
    else First Arrival
        IC-->>API: Claim Granted
        API->>API: Decrypt Ciphertext (Server Private Key)
        API->>API: Verify Freshness (TTL & Timestamp)
        
        API->>Core: Initiate Settlement
        Note over Core: Optimistic Locking (@Version)
        Core->>Core: Debit Sender / Credit Receiver
        Core-->>API: Transaction Committed
        API-->>B: 200 OK (Settled)
    end
```

---

## ✨ Core Features

*   **Hybrid Cryptography (RSA/AES):** Secures payment details from end-to-end. Intermediate nodes route ciphertext blindly without ever seeing PII or balances.
*   **Concurrent Idempotency:** Eliminates the "Double Spend" problem inherent in mesh flooding networks. Only the very first packet to reach the server processes; subsequent duplicate arrivals are atomically dropped.
*   **Optimistic Locking (ACID):** Database-level concurrency control guarantees consistent ledgers even under extreme load.
*   **TTL & Replay Protection:** Timestamp validation and finite hop-counts prevent infinite mesh loops and stale transaction attacks.
*   **Live Observability Dashboard:** View real-time topology, account balances, and settlement ledger updates dynamically.
*   **Production Scaffolding:** Containerized with Docker, equipped with CI/CD workflows, centralized error handling, and Swagger/OpenAPI documentation.

---

## 🛠️ Tech Stack

*   **Language:** Java 23
*   **Framework:** Spring Boot 3.3.5 (WebMVC, Data JPA)
*   **Database:** H2 (In-Memory) -> Scaffolding ready for PostgreSQL
*   **Documentation:** Springdoc OpenAPI (Swagger UI)
*   **Testing:** JUnit 5, Mockito, Spring Boot Test
*   **DevOps:** Docker, Docker Compose, GitHub Actions, Makefile

## 📚 Documentation

For more internal documentation, check out:
- [CHANGELOG.md](./CHANGELOG.md)
- [CONTRIBUTING.md](./CONTRIBUTING.md)
- [SECURITY.md](./SECURITY.md)

---
*Built with ❤️ for a connected, yet offline world.*
