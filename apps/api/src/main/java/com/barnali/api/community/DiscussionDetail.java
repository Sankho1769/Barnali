package com.barnali.api.community;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DiscussionDetail(UUID id, String slug, String title, String excerpt, String body, Instant createdAt,
                               UUID roomId, String roomName, String roomSlug, UUID authorId, String authorName,
                               String authorUsername, long replyCount, long voteCount, List<ReplySummary> replies) {
    public record ReplySummary(UUID id, String body, String authorName, Instant createdAt) {}
}
