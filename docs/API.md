# API reference

Base path: `/api/v1`. The controller source is authoritative. Examples below list routes and access; database behavior was not verified against a live service.

## Access rules

- Public read routes: no session required; public resources only.
- Auth endpoints: registration/login/recovery/reset/invitation acceptance are public; `/me`, logout, and password change require a session.
- Community mutations require an authenticated member session and CSRF token.
- Admin endpoints require an authenticated active admin and the named server permission. The admin UI middleware does not grant API access.
- Mutating requests send `X-XSRF-TOKEN`; `GET /auth/csrf` returns a token and sets the CSRF cookie. Credentialed CORS is restricted to `APP_ORIGIN`.

## Public and auth routes

| Method | Path | Access | Purpose |
| --- | --- | --- | --- |
| GET | `/public/discussions` | Public | Paginated discussions; `page`, `size` (1–50), `q`, `sort` (`latest`/`popular`), optional room. |
| GET | `/public/discussions/{slug}` | Public | Published discussion detail and replies. |
| GET | `/public/rooms` | Public | Public room list; limit max 20. |
| GET | `/public/rooms/{slug}` | Public | Public room detail. |
| GET | `/public/news/trending` | Public | Optional trusted-provider feed; returns `status: UNAVAILABLE` and no items when no server-side provider is configured. |
| GET | `/auth/csrf` | Public | CSRF token bootstrap. |
| POST | `/auth/register` | Public, rate-limited | Create member account. No role is accepted from client. |
| POST | `/auth/login` | Public, rate-limited | Create server session after credential check. |
| GET | `/auth/me` | Session | Current user and permission summary. |
| POST | `/auth/logout` | Session + CSRF | Invalidate session. |
| POST | `/auth/password/change` | Session + CSRF | Change password and revoke prior sessions. |
| POST | `/auth/password/recovery` | Public, rate-limited | Generic recovery response; mail is needed to deliver reset link. |
| POST | `/auth/password/reset` | Public, rate-limited by recovery flow | Consume single-use token and reset password. |
| POST | `/auth/admin-invitations/accept` | Public one-time invite token | Accept invitation and establish member/admin identity. |

## Community routes

| Method | Path | Permission | Purpose |
| --- | --- | --- | --- |
| GET | `/community/me/rooms` | Session | List rooms joined by current member. |
| POST | `/discussions` | Session + CSRF | Create a discussion in a joined room. |
| POST | `/rooms/{slug}/join` | Session + CSRF | Join a public room. |
| POST | `/discussions/{slug}/replies` | Session + CSRF | Add a reply. |
| POST | `/discussions/{slug}/vote` | Session + CSRF | Record/update vote. |
| POST | `/reports` | Session + CSRF | Report a supported target. |

## Admin routes

| Method | Path | Permission |
| --- | --- | --- |
| GET | `/admin/overview` | `OVERVIEW_READ` |
| GET | `/admin/analytics` | `ANALYTICS_READ` |
| GET | `/admin/users`, `/admin/users/{id}` | `USER_READ` |
| PATCH | `/admin/users/{id}/status` | `USER_SUSPEND` |
| GET | `/admin/reports` | `REPORT_READ` |
| POST | `/admin/reports/{id}/resolve` | `REPORT_RESOLVE` |
| GET | `/admin/audit` | `AUDIT_READ` |
| GET | `/admin/rooms` | `ROOM_READ` |
| POST | `/admin/rooms`, PATCH `/admin/rooms/{id}/active` | `ROOM_MANAGE` |
| GET | `/admin/team` | `ADMIN_TEAM_READ` |
| POST | `/admin/team/invitations`, `/admin/team/invitations/{id}/revoke`; PATCH `/admin/team/{id}/role`, `/admin/team/{id}/active` | Owner only |

Admin analytics accept `from` and `to` as ISO dates, default to the latest 30 days, and cap spans at 365 days. User, report, and audit endpoints paginate with bounded size. Error responses include a stable code and safe message; request validation errors include field messages. Internal stack traces and credentials are not returned.

No routes currently exist for bookmarks, follows, blocking, private messages, notifications, exports, or realtime events. There is no generated OpenAPI document; this page is hand-maintained from controller annotations and must be reconciled when controllers change.
