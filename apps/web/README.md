# BARNALI web application

Nuxt 4, Vue 3, and TypeScript application. Page routes are file-based in `app/pages`; shared UI, auth/API composables, route middleware, type definitions, and design CSS have their own adjacent READMEs. Public pages call the Spring API through `useApi`; authenticated operations require the server session and CSRF token. Database-backed content is unverified until an API/database is configured.

Run `npm install`, `npm run dev`, `npm run typecheck`, `npm test`, and `npm run build` from this folder. See `../../docs/DEVELOPMENT.md` for environment setup.
