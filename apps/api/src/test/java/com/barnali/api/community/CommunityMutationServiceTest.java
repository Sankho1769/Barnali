package com.barnali.api.community;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CommunityMutationServiceTest {
    @Test
    void slugsAreLowercaseAndUrlSafe() {
        assertEquals("knowledge-grows",CommunityMutationService.slug("Knowledge grows!"));
        assertEquals("discussion",CommunityMutationService.slug("日本語 ✨"));
        assertEquals("a-b-c",CommunityMutationService.slug("  A---B & C  "));
    }

    @Test
    void longSlugsStayWithinDatabaseLimit() {
        assertTrue(CommunityMutationService.slug("many ".repeat(100)).length()<=165);
    }
}
