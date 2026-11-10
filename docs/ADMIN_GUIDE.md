# Administrator guide

## Access and roles

Use `/admin/login` with an existing BARNALI account that has an active `admin_accounts` row. The server checks every protected endpoint; a UI route guard is not an authorization grant. Public registration creates members only. Owner bootstrap is controlled by `OWNER_EMAIL` and `OWNER_BOOTSTRAP_PASSWORD`; the first sign-in must change the bootstrap password. Never put a password, invitation URL, or reset token in documentation or a ticket.

| Role | Access |
| --- | --- |
| Owner | Overview, analytics, users/suspension, rooms, reports, admin team, audit. Only role allowed to invite, change delegated roles, or change delegated access. |
| Super Admin | Operational overview/analytics, users, rooms, reports, team read, audit; cannot modify the team. |
| Moderator | Overview, user read/suspension, room read, report read/resolve, audit. |
| Analytics Viewer | Overview/analytics and room read. |
| Support Admin | Overview, user read, audit. |

Permissions are a fixed role matrix; custom permission combinations do not exist. Owner cannot be removed/demoted through ordinary team APIs.

## Overview and analytics

Select a range from 7 to 365 days. Dates are UTC and the API caps ranges at 365 days. Metrics count actual database rows; an empty or unavailable result is not synthetic data. New registration/registration series use `users.created_at`. Active users/DAU/WAU/MAU use distinct `user_activity_events.user_id` values. DAU/WAU/MAU are current rolling UTC periods, independent of the selected chart range. Activity history begins when event recording begins; retention and comparisons are not shown.

## User and room operations

Search and paginate the user directory; sort by creation, name, or activity; inspect a user summary; suspend or reinstate a member with a reason. Admin rows cannot be changed through the member action. Create/activate/deactivate rooms according to role. Confirm that a change is reflected by the API response and audit log.

## Moderation

Review pending reports and choose a supported resolution. Hiding is supported for reported discussions/replies; dismissing reports is supported. User-target reports do not have a direct hide action. There is no general posts/replies queue or direct content search in the admin UI.

## Team and invitations

Only the owner can create invitations, change role, revoke invitations, and suspend/restore delegated admin access. Choose the least-privileged fixed role. Invitees use their own password or set one through the invitation flow; never share a permanent password. Invitation tokens are displayed once, expire after seven days, and should be sent only through a trusted channel. SMTP-based delivery is not configured by this workspace.

## Audit and unavailable features

Audit is read-only in the UI and backed by an append-only trigger. Admin actions require reasons where implemented. Notifications, export, system health/settings, MFA, custom roles, and data retention controls are not available. No live admin session or data has been verified in this workspace.
