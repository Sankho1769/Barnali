# UI and UX notes

## Page map

- **Public:** `/`, `/explore`, `/discussions`, `/discussions/:slug`, `/rooms`, `/rooms/:slug`, `/about`, `/community-guidelines`.
- **Account:** `/login`, `/register`, `/profile`, `/forgot-password`, `/reset-password`, `/account/password`.
- **Admin:** `/admin/login`, `/admin`, `/admin/users`, `/admin/users/:id`, `/admin/moderation`, `/admin/rooms`, `/admin/team`, `/admin/audit`, `/admin/access-denied`, `/admin/accept-invitation`.

## Layout and interaction

The public layout uses a desktop header, left community navigation, central content, and right discovery rail; the right rail hides at tablet widths and a bottom navigation is used on small screens. Admin pages use an administration header and permission-filtered sidebar. User/review tables can scroll within their table wrapper on narrow screens. The visual language reuses the BARNALI forest, ivory, teal, botanical assets, Georgia display type, and system/Inter body type.

Forms use labels, HTML constraints, disabled/loading states, and live error/status messages. Admin write operations wait for API responses; account status changes request a reason. Empty and service-error states do not insert sample records. No functioning dashboard is claimed without configured API/database data.

## SEO and privacy

Public home, explore, discussion, and room pages set titles/descriptions; discussion detail supplies Open Graph title/description. Admin, account, login, registration, recovery, and profile pages set noindex directives where implemented. Canonical link generation and sitemap are not configured. Private data protection depends on server-side guards and API authorization, not robots directives.

## Responsive verification

See [testing](TESTING.md) for exact viewport coverage. The current browser smoke test checks route status, document/body overflow, and visible controls outside the viewport. It does not perform visual-regression comparison, admin dashboard data-state testing, touch-device testing, or formal WCAG validation. The protected dashboard was not opened with a fabricated session.

## Accessibility follow-up

The asset kit includes `BARNALI_Asset_Kit/docs/ACCESSIBILITY_CHECKLIST.md`. Keyboard, screen-reader, color-contrast, reduced-motion, and 200% zoom checks remain **UNVERIFIED**. Run those checks in real routes and track issues before production.
