# Public news integration seam

`TrendingNewsProvider` is an optional server-side adapter for a trusted source. No provider or API key is configured in this workspace. In that state, `GET /api/v1/public/news/trending` returns `UNAVAILABLE` with an empty item list; the service does not synthesize headlines. Provider entries require source-supplied publisher, timestamp, article URL, and headline, with HTTPS-only article and optional thumbnail URLs.

Keep provider credentials in server-side environment/secret configuration. Add an adapter only after a source is approved and its attribution, freshness, caching, and terms are reviewed.
