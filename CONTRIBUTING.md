# Contributing to TrustMesh

First off, thank you for considering contributing to TrustMesh! It's people like you that make TrustMesh such a great tool.

## 1. Where do I go from here?

If you've noticed a bug or have a feature request, make sure to check our [Issues](../../issues) to see if someone else has already created a ticket. If not, go ahead and [make one](../../issues/new)!

## 2. Fork & create a branch

If this is something you think you can fix, then fork TrustMesh and create a branch with a descriptive name.

A good branch name would be (where issue #325 is the ticket you're working on):

```sh
git checkout -b 325-add-redis-idempotency
```

## 3. Implementation Guidelines

- **Idempotency:** Any new endpoints dealing with payments MUST go through the idempotency layer.
- **Security:** Do not log PII or raw transaction data. Always use the logger rather than `System.out`.
- **Tests:** All code must have corresponding tests. Aim for >85% coverage.

## 4. Submitting a Pull Request

Please use the provided PR template. Ensure all GitHub Actions (CI) checks pass before requesting a review.
