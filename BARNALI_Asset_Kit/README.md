# BARNALI Complete Brand & UI Asset Package

**Version:** 1.1.0  
**Brand direction:** Creative, bold, modern, with a botanical identity  
**Frontend target:** Vue 3 + TypeScript + Vite  
**Backend target:** Java Spring Boot REST API

## Quick start
1. Unzip the package.
2. Copy `brand/` and `ui/` to your frontend `public/assets/` folder, preserving directory structure.
3. Import `design-system/barnali.css` into your Vue app, or import the theme CSS and token files you prefer.
4. Copy `frontend-vue/src/components/BarnaliBrand.vue` into your Vue source tree.
5. Open `screens/responsive-dashboard-preview.html` in a browser to inspect the static responsive layout concept.
6. Read `docs/ASSET_CATALOG.md`, `docs/FILE_NAMING.md`, and `docs/INTEGRATION_GUIDE.md`.

## Brand colors
- Deep Forest Green: `#073B2A`
- Electric Teal: `#00AEBB`
- Warm Ivory: `#F9F5EA`
- Vermilion: `#E65336`
- Ink: `#17221D`

For small text and links, use the darker teal `#007780` rather than bright Electric Teal. Vermilion is a supporting accent, not the only way to communicate errors or destructive actions.

## Package structure
- `brand/logos/`: logo lockups, mark variants, wordmark and favicon.
- `brand/social/`: social share card.
- `brand/launch/`: release/export notes.
- `ui/icons/`: navigation and action icons.
- `ui/avatars/`: avatar placeholders.
- `ui/badges/`: status and community badges.
- `ui/illustrations/`: empty states and verification notices.
- `design-system/`: tokens, CSS, light/dark themes.
- `frontend-vue/`: Vue starter component and CSS helpers.
- `screens/`: responsive dashboard concept and feature screen folders.
- `docs/`: asset catalog, naming rules, integration guide, accessibility and release checklist.

## Honest scope note
This is a brand/UI asset and integration starter, not a complete application. It does not implement Spring Boot APIs, login flows, database, real-time notifications, or fully designed production screens for every route. Empty folders for future screen groups are organizational placeholders. The included HTML is a static concept and should be adapted to your real codebase.
