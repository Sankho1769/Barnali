# Admin unit tests

`PermissionMatrixTest` checks role-to-permission grants/denials. `AdminAuthorizationTest` verifies the owner guard uses the query that requires an active user with the bootstrap password change completed. These unit tests do not exercise Spring method security or database-backed admin routes; those require integration tests.
