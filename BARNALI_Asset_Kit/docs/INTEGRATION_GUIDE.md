# Integration guide

## Vue 3 + TypeScript + Vite
1. Copy `brand/` and `ui/` into `frontend/public/assets/`.
2. Copy `design-system/barnali.css` into `frontend/src/styles/` and import it in `main.ts`.
3. Copy `frontend-vue/src/components/BarnaliBrand.vue` into `frontend/src/components/`.
4. Update the logo paths to `/assets/brand/logos/barnali-mark.svg` if the public assets are copied as recommended.
5. Use the design tokens in components instead of hardcoding repeated colors.

## Spring Boot
Serve API routes separately from static UI assets where practical. For development, Vite serves the frontend and Spring Boot serves the API. In production, deploy the built frontend behind a web server/CDN and Spring Boot as the API service, or configure Spring Boot to serve the built frontend if your deployment is intentionally single-service. Protect API resources with Spring Security; UI assets do not replace authentication or authorization.

## Responsive design
- 320–639 px: single column; compact header; bottom navigation.
- 640–1023 px: two-column layout; hide or drawer the right rail.
- 1024 px and above: left navigation + central feed + right discovery rail.
- At 200% zoom, avoid horizontal scrolling for primary reading and forms.

## Asset export
SVG is the source of truth for logos/icons. Export PNG only for platforms that do not support SVG. For app store icons, validate each platform's required dimensions and safe area before release. No raster exports are included in this package.

## Security
Do not place secrets, credentials, API keys, or user data in this asset package. Never use decorative client-side UI state as a substitute for server-side authorization.
