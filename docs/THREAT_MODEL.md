# Threat model

**Scope:** current Nuxt browser client, Spring Boot API, PostgreSQL schema, owner bootstrap, admin routes, and invitation/recovery paths. External deployment infrastructure is not attached.

## Assets

- Member credentials, session cookies, account email/status, and community content.
- Admin identities, roles, moderation powers, invitation/reset tokens, and audit history.
- PostgreSQL data and migration/backup credentials.
- Aggregate activity data used in admin analytics.

## Trust boundaries

1. Browser to API: untrusted input; session cookie and CSRF protections apply.
2. Spring service to PostgreSQL: trusted backend connection; database account privileges are operationally configured.
3. Public reads to admin data: endpoint permissions and no-store headers must prevent leakage; SEO metadata is not access control.
4. Owner to delegated admin: invitation tokens are bearer secrets; owner-only APIs determine grant/revoke.
5. App to mail provider: optional and currently unconfigured; reset links and invitations are unavailable over email until configured.

## Threats and controls

| Threat | Source controls | Residual risk/status |
| --- | --- | --- |
| Password guessing/credential stuffing | HMAC-keyed identity/address buckets, generic login errors, BCrypt 12 | No captcha/MFA; proxy IP behavior needs production setup. |
| CSRF/session theft | CSRF token, HttpOnly SameSite cookie, rotation, logout, 8-hour timeout, auth-version validation | HTTPS/proxy and deployed cookie behavior are unverified. |
| Self-assigned admin privilege | Roles stored in `admin_accounts`, method authorization, registration is member-only, owner-only team changes | RBAC API integration tests are absent. |
| Owner takeover through bootstrap collision | Bootstrap guard, unique owner index, existing owner short-circuit, collision refusal | Recovery must be operationally controlled; DB is not configured here. |
| Invitation replay or database token leak | Random token, hash at rest, expiry, one-time accept/revoke | Email delivery not set; manually shared token must remain private. |
| Recovery account enumeration/replay | Generic response, hashed one-time 30-minute token | SMTP absent; reset path untested against DB. |
| SQL injection / unbounded query | JDBC bind parameters, sort allowlist, paging/date bounds, statement timeout | No live query plans/load test. |
| Public cache or SSR leakage | Private API no-store, admin noindex, server-side API auth | SSR proxy behavior and production CDN rules need deployment testing. |
| Audit tampering | Append-only trigger and application append path | Database owner can alter trigger/storage; external immutable backup not configured. |
| Sensitive analytics overcollection | Minimal event type/user/time records; no message body/device fingerprint | Retention policy and legal/privacy review remain pending. |

## Security tests needed

Test unauthenticated and non-admin denial on every protected endpoint; every role/permission; owner protection; session fixation/logout/revocation; CSRF; invitation races/replay/expiry; recovery replay; SQL boundaries; migration permissions; audit append-only behavior; proxy/CORS/cookie policy; and privacy leakage in SSR/cache. Current tests are listed in [Testing](TESTING.md).
