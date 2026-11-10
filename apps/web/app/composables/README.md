# Web composables

`useApi.ts` sends requests to the configured Spring API and includes browser cookies/CSRF on mutations; `useAuth.ts` holds session-derived client state. Never store admin credentials or a database key in these composables. Server routes remain authoritative.
