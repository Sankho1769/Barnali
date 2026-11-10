package com.barnali.api.community;

import java.util.UUID;

public record RoomSummary(UUID id, String slug, String name, String description, long memberCount, long discussionCount) {}
