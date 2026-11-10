package com.barnali.api.community;

import java.time.Instant;
import java.util.UUID;

public record DiscussionSummary(UUID id, String slug, String title, String excerpt, Instant createdAt,
                                String roomName, String roomSlug, String authorName, String authorUsername,
                                long replyCount, long voteCount) {}
