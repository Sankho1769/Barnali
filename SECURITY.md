# Security policy and posture

## Reporting and secrets

Do not open public issues containing passwords, sessions, reset/invitation tokens, database URLs, SMTP credentials, private user data, or exploit details. Revoke/rotate exposed credentials immediately and use a private channel controlled by the deployment owner. No production deployment/contact address is configured in this workspace.

## Current security posture

This source includes Spring Security server sessions, CSRF, BCrypt, HMAC-keyed rate limits, fixed server-side admin permissions, owner-only team management gated on active status and completed bootstrap password change, shared strong-password policy, hashed one-time tokens, and an append-only audit trigger. It does not include MFA, verified email, SMTP configuration, security integration tests, or an attached database. Production use is **not ready**.

See the full [security guide](docs/SECURITY.md), [threat model](docs/THREAT_MODEL.md), and [security review](SECURITY_REVIEW.md). Production blockers and verification requirements are listed there.
