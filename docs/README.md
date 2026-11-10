# BARNALI documentation

This documentation describes the source currently in this workspace. `IMPLEMENTED` means code exists; it does not imply live database or production verification. `UNVERIFIED` means external infrastructure or a relevant integration test is missing. `PLANNED` means the feature is not present.

## Start here

- [Product requirements](PRD.md) and [software requirements](SRS.md)
- [Architecture](ARCHITECTURE.md), [database](DATABASE.md), and [API](API.md)
- [Database access-control matrix](DATABASE_ACCESS_CONTROL_MATRIX.md) (all 14 tables and source query paths)
- [UI/UX](UI_UX.md) and [design system](DESIGN_SYSTEM.md)
- [Development](DEVELOPMENT.md), [testing](TESTING.md), and [deployment](DEPLOYMENT.md)
- [Security](SECURITY.md) and [threat model](THREAT_MODEL.md)
- [User guide](USER_GUIDE.md), [admin guide](ADMIN_GUIDE.md), and [feature dependencies](FEATURE_DEPENDENCIES.md)
- [Documentation checklist](DOCUMENTATION_CHECKLIST.md)
- [Next-phase source gap analysis](NEXT_PHASE_GAP_ANALYSIS.md)
- Existing implementation evidence: root [website audit](../WEBSITE_AUDIT_REPORT.md), [security review](../SECURITY_REVIEW.md), and [admin implementation report](../ADMIN_PANEL_IMPLEMENTATION_REPORT.md)
- Local variables and startup: [environment setup](ENVIRONMENT_SETUP.md)

## Scope and evidence

The frontend is Nuxt 4/Vue 3/TypeScript. The backend is Spring Boot 4 targeting Java 21, Spring Security, JDBC, Flyway, and PostgreSQL. The preserved `BARNALI_Asset_Kit` has its own catalog, accessibility, naming, and integration documents; those originals have not been edited.

No live PostgreSQL/Supabase, SMTP, MFA provider, production host, or Git metadata is attached. Database-backed behavior, migrations, delivery, deployment, and release history are therefore not claimed as verified. A separate documentation master prompt referenced by the project-build prompt was not present in this workspace or the supplied text attachments; the current build prompt's documentation requirements were used.
