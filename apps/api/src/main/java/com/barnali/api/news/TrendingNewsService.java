package com.barnali.api.news;

import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class TrendingNewsService {
    private static final int ITEM_LIMIT = 5;

    private final ObjectProvider<TrendingNewsProvider> providers;

    public TrendingNewsService(ObjectProvider<TrendingNewsProvider> providers) {
        this.providers = providers;
    }

    public TrendingNewsResponse trending() {
        TrendingNewsProvider provider = providers.getIfAvailable();
        if (provider == null) return TrendingNewsResponse.unavailable();
        List<TrendingNewsItem> items = provider.trending(ITEM_LIMIT);
        return TrendingNewsResponse.available(items == null ? List.of() : items.stream().limit(ITEM_LIMIT).toList());
    }
}
