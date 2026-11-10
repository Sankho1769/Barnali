package com.barnali.api.community;

public record RoomDetail(RoomSummary room, PageResult<DiscussionSummary> discussions) {}
