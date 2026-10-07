# Release 0.5.0

linkly v0.4 -> v0.5: security fix, click accuracy fix, link expiry

## Changes

- Black-box regression tests for LINK-142/150/151, written before any fix (test-first)
- LINK-142: reject script/data/credential URLs at the API boundary (new validator)
- LINK-151: atomic click increment; 302 + no-store so browsers stop caching redirects
- LINK-150: optional expires_in_days; 410 Gone after expiry; additive schema migration

## Evidence

- [x] all tests pass: 11 passed / 0 failed
- [x] line coverage >= 85.0%: 96.1%
- [x] checkstyle clean: 0 issues
- [x] no security findings: 0 findings
- [x] docs generated: openapi.json, docs/API.md, docs/DESIGN.md, CHANGELOG.md
- [x] no blocking questions open: []

## Known risks / follow-ups

- LinkStore.create: codes derived from sequential row ids can be enumerated
