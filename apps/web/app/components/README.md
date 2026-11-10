# Shared Vue components

Reusable application UI: `BarnaliBrand`, headers/navigation, discussion cards, the typed `TrendingNewsPanel`, community right rail, empty state, icons, admin shell, KPI card, and trend chart. Discussion vote/report actions call the Spring API; permissions and persisted mutations remain authoritative there. The news panel receives provider-sourced items only and displays an unavailable state when no trusted provider is configured. Use the BARNALI tokens documented in `../../../../docs/DESIGN_SYSTEM.md`.
