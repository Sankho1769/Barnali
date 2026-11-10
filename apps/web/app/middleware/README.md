# Route middleware

`member.ts` and `admin.ts` redirect for user experience. They are not security boundaries: `ApiSecurityConfiguration` and method-level authorization in Spring must deny unauthorized API calls independently.
