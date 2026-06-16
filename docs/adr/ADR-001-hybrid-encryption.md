# ADR-001: Hybrid Encryption (RSA-OAEP + AES-256-GCM)

**Date:** 2024-01-15  
**Status:** Accepted  
**Deciders:** TrustMesh Core Team

---

## Context

TrustMesh needs to encrypt UPI payment payloads on the sender's device so that untrusted relay phones — which physically carry the packet via BLE gossip — cannot read the payment details (sender VPA, receiver VPA, amount, PIN hash).

The payload is a JSON object typically 300–500 bytes in size.

We need:
1. **Confidentiality** — relay nodes see only an opaque ciphertext blob
2. **Integrity** — any tampering must be detectable at the server
3. **Asymmetric trust** — only the server (private key holder) can decrypt; any phone can encrypt using the public key

---

## Decision

Use **Hybrid Encryption**: RSA-2048/OAEP for key encapsulation + AES-256-GCM for payload encryption.

Wire format:
```
[256 bytes RSA-encrypted AES key] [12 bytes AES-GCM IV] [N bytes AES-GCM ciphertext + 16-byte auth tag]
```
All concatenated and Base64-encoded for JSON transport.

---

## Rejected Alternatives

### Option A: Pure RSA
**Rejected because:**
- RSA-2048 can encrypt at most ~245 bytes (OAEP padding overhead)
- Our JSON payload is 300–500 bytes — exceeds RSA block size
- RSA is orders of magnitude slower than AES for bulk data
- This is the exact problem hybrid encryption was designed to solve (see: TLS handshake, PGP, Signal Protocol)

### Option B: Pure AES (Symmetric)
**Rejected because:**
- Requires a shared secret between sender and server
- No secure channel exists to share this secret offline
- Any device that can encrypt can also decrypt — zero trust model broken

### Option C: ECIES (Elliptic Curve Integrated Encryption Scheme)
**Considered** — ECIES with ECDH + AES-GCM is the modern recommendation (smaller keys, faster).  
**Deferred** — Java JCE has excellent RSA support out of the box; ECIES requires Bouncy Castle. For a demo, RSA-2048 is sufficient and avoids an extra dependency.  
**Production recommendation:** Migrate to ECDH-P256 + AES-256-GCM (same approach, 32-byte public key vs 256-byte RSA key).

---

## Consequences

✅ **Positive:**
- Relay phones are cryptographically blind to payment details
- AES-GCM authentication tag detects any 1-bit tampering instantly
- Per-packet fresh AES key provides forward secrecy at packet granularity
- Standard Java JCE — no external crypto dependency

⚠️ **Negative/Trade-offs:**
- Server must hold RSA private key securely (in demo: in JVM memory at startup; in production: AWS KMS / HashiCorp Vault)
- If the server's RSA private key leaks, all historical ciphertexts can be decrypted (no perfect forward secrecy at the session level)
- 256-byte RSA overhead per packet is non-trivial for very small payloads

---

## References
- [NIST SP 800-56B Rev 2 — RSA Key Establishment](https://nvlpubs.nist.gov/nistpubs/SpecialPublications/NIST.SP.800-56Br2.pdf)
- [RFC 8017 — PKCS#1 (RSAES-OAEP)](https://datatracker.ietf.org/doc/html/rfc8017)
- [TLS 1.3 Key Exchange — similar hybrid approach](https://datatracker.ietf.org/doc/html/rfc8446#section-7.4)
