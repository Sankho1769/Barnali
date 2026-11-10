package com.barnali.api.news;

import java.util.List;

/**
 * Optional server-side adapter for a trusted news source.
 *
 * Implementations must return source-provided article metadata and HTTPS URLs.
 * Provider credentials must remain in server configuration.
 */
public interface TrendingNewsProvider {
    List<TrendingNewsItem> trending(int limit);
}
