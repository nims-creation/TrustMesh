# Security Policy

## Supported Versions

Currently, only the latest version of TrustMesh is supported with security updates.

| Version | Supported          |
| ------- | ------------------ |
| 1.0.x   | :white_check_mark: |
| < 1.0   | :x:                |

## Reporting a Vulnerability

Security is a top priority for TrustMesh. We treat security vulnerabilities with the utmost seriousness.

If you discover a security vulnerability within TrustMesh, please send an e-mail to our security team. Do not open a public issue.

### Triage Process
1. Acknowledge receipt within 48 hours.
2. Investigate and confirm the vulnerability.
3. Develop and test a patch.
4. Release the patch and publish a security advisory.

## Implemented Security Features
- **HSTS & Security Headers:** Enforced globally.
- **Asymmetric Encryption (RSA):** Payloads encrypted at rest/in-transit via mesh.
- **Idempotency:** Prevent double-spend attacks from duplicate packet delivery.
- **Strict Validation:** Input sanitized via `jakarta.validation`.
