package com.barnali.api.news;

import java.util.List;

public record TrendingNewsResponse(Status status, List<TrendingNewsItem> items) {
    public enum Status { AVAILABLE, UNAVAILABLE }

    public TrendingNewsResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static TrendingNewsResponse unavailable() {
        return new TrendingNewsResponse(Status.UNAVAILABLE, List.of());
    }

    public static TrendingNewsResponse available(List<TrendingNewsItem> items) {
        return new TrendingNewsResponse(Status.AVAILABLE, items);
    }
}
