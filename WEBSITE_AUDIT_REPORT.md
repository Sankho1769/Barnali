# BARNALI website audit report

**Date:** 2026-10-22  
**Scope:** Current `C:\Users\hp\Desktop\Barnali` workspace, including the preserved brand kit, Nuxt app, Spring API, SQL migrations, and available local verification. This is a source audit; no production service was attached.

## Result

**PASS with documented gaps.** The asset kit is preserved and the working application source now lives beside it. The site has public community routes, authentication forms, discussion and room views, and protected admin pages backed by API calls. Empty, loading, and unavailable states are used instead of bundled people, posts, or analytics. Public discussion detail routes fetch server-side data and set page-specific social/SEO metadata. Admin pages set `noindex,nofollow` and require server-side authorization for their API data.

Original assets remain under `BARNALI_Asset_Kit`. Application copies include 43 files under `apps/web/public/assets` and the original brand styles and Vue brand component under `apps/web/app`. The asset kit includes logos, icons, badges, illustrations, design tokens, and the supplied login/feed mockups. `BGC.docx` was not used as product requirements.

## Main routes and source

- Public: `/`, `/explore`, `/discussions`, `/discussions/:slug`, `/rooms`, `/rooms/:slug`, `/about`, `/community-guidelines`, `/login`, `/register`, `/profile`, `/forgot-password`, `/reset-password`, and `/account/password`.
- Admin: `/admin/login`, `/admin`, `/admin/users`, `/admin/users/:id`, `/admin/moderation`, `/admin/rooms`, `/admin/team`, `/admin/audit`, `/admin/access-denied`, and `/admin/accept-invitation`.
- Frontend: `apps/web/app/pages`, `components`, `composables`, `middleware`, and `assets/css`.
- Backend: `apps/api/src/main/java/com/barnali/api`.
- Data model: `apps/api/src/main/resources/db/migration`.
- Setup: `README.md`, `.env.example`, `docs/ENVIRONMENT_SETUP.md`, and `docs/ARCHITECTURE.md`.

## Findings by severity

| Severity | Status | Finding |
| --- | --- | --- |
| High | BLOCKED | Supabase/PostgreSQL credentials are not configured. Flyway migrations, live SSR data, registration, authentication, moderation, invitations, and analytics were not integration-tested against a database. |
| High | FAIL | MFA for owner and delegated administrators is not implemented. Treat deployment with real user data as blocked until an MFA flow and recovery process are added or an accepted compensating control is documented. |
| Medium | FAIL | Email verification, automated invitation email, admin notification delivery, exports, system-event/health pages, settings, and custom permission combinations are not implemented. No empty navigation pages were added for these. |
| Medium | BLOCKED | No staging deployment, backup/restore drill, migration rehearsal, or production monitoring endpoint was available to audit. |
| Low | PASS | The source tree uses the intended Nuxt/Vue/TypeScript frontend and Spring Boot/PostgreSQL backend; the original asset kit remains intact. |

## Validation performed

- Nuxt typecheck: passed, exit code 0.
- Nuxt production build: passed with Nuxt 4.6.2 using the bundled Node runtime.
- Frontend unit tests: 2 passed, 0 failed.
- Backend unit tests: see `ADMIN_PANEL_IMPLEMENTATION_REPORT.md` for the final run count.
- Browser responsive smoke: 180 viewport checks across `/`, `/explore`, `/login`, `/rooms`, `/admin/login`; widths 320, 360, 375, 390, 430, 768, 1024, 1280, 1440, 1920, 2560, 3440 CSS px and heights 600, 900, 1200 px. No horizontal overflow or visible controls outside the viewport was found.
- No live API/database, migration integration, formal accessibility audit, or manual screen-reader check was performed.

## Production verdict

**Not production-ready.** Source and local checks are in place, but deployment requires a provisioned PostgreSQL/Supabase project, verified migrations and auth flows, MFA, SMTP/recovery decisions, staging checks, and operational backup/security controls. See `SECURITY_REVIEW.md` and `ADMIN_PANEL_IMPLEMENTATION_REPORT.md`.
