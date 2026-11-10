# Transaction-bound database identity

`DatabaseIdentityContext` reads identity only from an authenticated `AppPrincipal` in Spring Security. Its scoped anonymous mode is used by public authentication and invitation endpoints so an old session cannot become the identity for login lookup, registration, recovery/reset, or invitation acceptance.

`BarnaliJdbcTransactionManager` binds `barnali.user_id` with PostgreSQL's transaction-local `set_config` on Spring's bound JDBC connection. Hikari initializes each physical connection with an empty identity baseline; the transaction-local setting restores that baseline on commit or rollback. It sets an empty value when no principal is present and rolls back/unbinds if identity setup fails. It never passes an admin role to PostgreSQL. Protected reads must run through Spring's proxied transaction services to receive the identity.

This source code is only the application half of the model. V1/V2 create no RLS policies or pre-authentication functions, so default-deny RLS will still block database operations. Do not add permissive policies or grant BYPASSRLS to compensate. See `docs/SECURITY.md` and `docs/DATABASE.md` for the unresolved database-side design and test requirements.
