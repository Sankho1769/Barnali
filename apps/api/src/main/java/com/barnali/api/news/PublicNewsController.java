package com.barnali.api.news;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/news")
public class PublicNewsController {
    private final TrendingNewsService news;

    public PublicNewsController(TrendingNewsService news) {
        this.news = news;
    }

    @GetMapping("/trending")
    public ResponseEntity<TrendingNewsResponse> trending() {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(news.trending());
    }
}
