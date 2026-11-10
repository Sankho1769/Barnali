# API TypeScript types

`api.ts` defines client-side response/request shapes for the Spring API, including the optional trusted-news response. Keep these aligned with controller DTOs in `apps/api`; this folder is not a runtime validation layer and database/service behavior must not be inferred from a TypeScript type.
