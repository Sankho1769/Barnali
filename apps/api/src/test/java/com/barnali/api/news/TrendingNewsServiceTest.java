package com.barnali.api.news;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class TrendingNewsServiceTest {
    @Test
    void returnsUnavailableWhenNoTrustedProviderIsConfigured() {
        ObjectProvider<TrendingNewsProvider> providers = mock(ObjectProvider.class);
        when(providers.getIfAvailable()).thenReturn(null);

        TrendingNewsResponse response = new TrendingNewsService(providers).trending();

        assertEquals(TrendingNewsResponse.Status.UNAVAILABLE, response.status());
        assertTrue(response.items().isEmpty());
    }

    @Test
    void limitsProviderResultsToFiveItems() {
        ObjectProvider<TrendingNewsProvider> providers = mock(ObjectProvider.class);
        TrendingNewsProvider provider = mock(TrendingNewsProvider.class);
        when(providers.getIfAvailable()).thenReturn(provider);
        List<TrendingNewsItem> items = java.util.stream.IntStream.range(0, 7)
                .mapToObj(i -> new TrendingNewsItem("Story " + i, "Publisher", Instant.parse("2026-10-22T00:00:00Z"),
                        URI.create("https://example.org/story/" + i), null))
                .toList();
        when(provider.trending(5)).thenReturn(items);

        TrendingNewsResponse response = new TrendingNewsService(providers).trending();

        assertEquals(TrendingNewsResponse.Status.AVAILABLE, response.status());
        assertEquals(5, response.items().size());
        assertEquals("Story 0", response.items().getFirst().headline());
    }

    @Test
    void rejectsNonHttpsArticleAndThumbnailUrls() {
        Instant publishedAt = Instant.parse("2026-10-22T00:00:00Z");
        assertThrows(IllegalArgumentException.class, () -> new TrendingNewsItem("Story", "Publisher", publishedAt,
                URI.create("http://example.org/story"), null));
        assertThrows(IllegalArgumentException.class, () -> new TrendingNewsItem("Story", "Publisher", publishedAt,
                URI.create("https://example.org/story"), URI.create("javascript:alert(1)")));
        assertFalse(new TrendingNewsItem("Story", "Publisher", publishedAt,
                URI.create("https://example.org/story"), null).headline().isBlank());
    }
}
