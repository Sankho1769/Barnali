# BARNALI design system

The source of truth is `BARNALI_Asset_Kit/design-system/tokens/barnali.tokens.json`, with copied styles in `apps/web/app/assets/css`. Preserve the original kit; update application copies intentionally when tokens change.

## Brand and semantic colors

| Token | Value | Use |
| --- | --- | --- |
| Forest green / primary | `#073B2A` | Primary actions, selected navigation, brand emphasis |
| Electric teal / accent | `#00AEBB` | Accent and visible focus ring; avoid for small text |
| Dark teal / accent text | `#007780` | Links and small accent text |
| Warm ivory / background | `#F9F5EA` | Warm page canvas |
| Vermilion | `#E65336` | Supporting accent; currently used for the selected feed marker, room marker, and confirmed vote state; do not signal meaning by color alone |
| Ink | `#17221D` | Main text |
| White | `#FFFFFF` | Main surfaces |
| Pale teal | `#D7F1EF` | Selected/secondary surfaces |
| Sage | `#E7EEE7` | Selected navigation and soft accents |
| Border | `#DCDDD2` | Controls, cards, table boundaries |
| Muted | `#68736B` | Secondary text |

Semantic success/warning/danger tokens are in `barnali-light.css` and `app.css`: success `#216E4A`, warning `#79520A`, danger `#A83220` with pale companion backgrounds.

## Typography and geometry

- Display: Georgia / Times New Roman serif.
- Body: Inter with system sans-serif fallbacks.
- Base scale: 12, 14, 16, 18, 24, 32, 40 px.
- Spacing uses 4 px increments (4–64 px); radii are 8/12/18/24 px and pill.
- Content max-width: 1600 px in tokens; application community grid currently caps at 1520 px.
- Breakpoints: 320–639 mobile, 640–1023 tablet, 1024+ desktop.
- Minimum touch target and focus-ring guidance in tokens: 44 px and 3 px teal outline.

## Components and asset rules

Shared Vue components are in `apps/web/app/components`. Use `BarnaliBrand` and `IconGlyph` rather than duplicating branding/icon treatment. Use dark teal for small links, visible focus states, text labels alongside status color, and empty alt text for decorative icons. Original SVG assets under `BARNALI_Asset_Kit` are the source of truth; app copies live under `apps/web/public/assets`.

## Verification status

The in-app browser confirmed the navigation and right-rail breakpoint states at the requested viewport matrix; full horizontal-overflow assertions remain blocked because the standalone Playwright browser cannot reach the local server. Token pair contrast, all interaction states, reduced motion, and WCAG conformance have not been formally measured.
