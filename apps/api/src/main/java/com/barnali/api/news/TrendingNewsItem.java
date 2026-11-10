package com.barnali.api.news;

import java.net.URI;
import java.time.Instant;

public record TrendingNewsItem(
        String headline,
        String publisher,
        Instant publishedAt,
        URI articleUrl,
        URI thumbnailUrl) {

    public TrendingNewsItem {
        headline = requiredText(headline, 240, "headline");
        publisher = requiredText(publisher, 120, "publisher");
        if (publishedAt == null) throw new IllegalArgumentException("publishedAt is required.");
        articleUrl = requireHttps(articleUrl, "articleUrl");
        if (thumbnailUrl != null) thumbnailUrl = requireHttps(thumbnailUrl, "thumbnailUrl");
    }

    private static String requiredText(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength) {
            throw new IllegalArgumentException(field + " must contain 1 to " + maxLength + " characters.");
        }
        return value.trim();
    }

    private static URI requireHttps(URI value, String field) {
        if (value == null || !value.isAbsolute() || !"https".equalsIgnoreCase(value.getScheme())
                || value.getHost() == null || value.getUserInfo() != null) {
            throw new IllegalArgumentException(field + " must be an absolute HTTPS URL without embedded credentials.");
        }
        return value.normalize();
    }
}
