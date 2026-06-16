# ADR-002: AES-256-GCM over AES-256-CBC

**Date:** 2024-01-15  
**Status:** Accepted  
**Deciders:** TrustMesh Core Team

---

## Context

Once we commit to hybrid encryption (ADR-001), we need to choose the AES mode for payload encryption. AES has many modes of operation. The choice determines whether we get **authentication** (tamper detection) in addition to confidentiality.

In TrustMesh's threat model, packets travel through untrusted relay phones. Any relay node could attempt to flip bits in the ciphertext hoping to:
1. Corrupt the payment silently (wrong amount reaches the server)
2. Probe decryption failures for oracle attacks

---

## Decision

Use **AES-256-GCM** (Galois/Counter Mode) — an Authenticated Encryption with Associated Data (AEAD) cipher.

```java
Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
GCMParameterSpec spec = new GCMParameterSpec(128, iv); // 128-bit auth tag
```

---

## Rejected Alternatives

### Option A: AES-256-CBC (Cipher Block Chaining)
**Rejected because:**
- CBC provides **confidentiality only** — no integrity check
- A tampered ciphertext decrypts to garbage without raising an exception
- Vulnerable to **CBC padding oracle attacks** (Vaudenay 2002, BEAST, POODLE)
- Server would silently process a corrupted payment instruction

### Option B: AES-256-CTR (Counter Mode)
**Rejected because:**
- Like CBC, CTR provides confidentiality only — no authentication
- Bit-flipping attacks are trivially effective against CTR mode (1-bit flip in ciphertext = exact 1-bit flip in plaintext)

### Option C: ChaCha20-Poly1305
**Considered** — Modern AEAD, hardware-accelerated on mobile, used in TLS 1.3 and WireGuard.  
**Deferred** — Java JCE supports ChaCha20-Poly1305 from Java 11+, but GCM has wider hardware acceleration on x86 (AES-NI). For a backend demo running on server hardware, GCM is the natural choice.  
**Production recommendation:** ChaCha20-Poly1305 for mobile client encryption, AES-256-GCM for server-side.

---

## Consequences

✅ **Positive:**
- `AEADBadTagException` thrown immediately on any 1-bit tamper — relay attack is impossible
- GCM is hardware-accelerated via AES-NI instruction on modern CPUs (near-zero overhead)
- Same cipher used in TLS 1.3, QUIC, SSH — battle-tested
- Auth tag (16 bytes) included in the wire format — no separate MAC needed

⚠️ **Negative/Trade-offs:**
- GCM IV (nonce) **must never be reused** with the same key. Since we generate a fresh AES key per packet, IV reuse is impossible — but this constraint must be documented
- GCM is slightly slower than CTR for very long messages (auth tag computation), but payload size is <1KB so this is immaterial

---

## References
- [NIST SP 800-38D — GCM specification](https://nvlpubs.nist.gov/nistpubs/Legacy/SP/nistspecialpublication800-38d.pdf)
- [Vaudenay Padding Oracle Attack (2002)](https://link.springer.com/chapter/10.1007/3-540-46035-7_35)
- [TLS 1.3 RFC 8446 — mandates AEAD](https://datatracker.ietf.org/doc/html/rfc8446#appendix-B.4)
