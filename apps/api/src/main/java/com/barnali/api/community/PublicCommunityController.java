package com.barnali.api.community;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/public")
public class PublicCommunityController {
    private final CommunityService community;

    public PublicCommunityController(CommunityService community) {
        this.community = community;
    }

    @GetMapping("/discussions")
    public ResponseEntity<PageResult<DiscussionSummary>> discussions(
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "latest") String sort,
            @RequestParam(required = false) String room) {
        if (!List.of("latest", "popular").contains(sort.toLowerCase())) sort = "latest";
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(community.discussions(page, size, q, sort, room));
    }

    @GetMapping("/discussions/{slug}")
    public ResponseEntity<DiscussionDetail> discussion(@PathVariable String slug) {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(community.discussion(slug));
    }

    @GetMapping("/rooms")
    public ResponseEntity<List<RoomSummary>> rooms(@RequestParam(defaultValue = "20") @Min(1) @Max(20) int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(community.rooms(limit));
    }

    @GetMapping("/rooms/{slug}")
    public ResponseEntity<RoomDetail> room(@PathVariable String slug) {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(community.room(slug));
    }
}
