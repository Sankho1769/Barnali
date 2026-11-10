package com.barnali.api.community;

import com.barnali.api.auth.AppPrincipal;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class CommunityMutationController {
    private final CommunityMutationService mutations;

    public CommunityMutationController(CommunityMutationService mutations) { this.mutations = mutations; }

    @GetMapping("/community/me/rooms")
    public java.util.List<RoomSummary> myRooms(Authentication authentication) {
        return mutations.myRooms(principal(authentication));
    }

    @PostMapping("/discussions")
    public ResponseEntity<CommunityMutationService.CreatedDiscussion> createDiscussion(
            @Valid @RequestBody CommunityMutationService.CreateDiscussionRequest input, Authentication authentication) {
        var created = mutations.createDiscussion(principal(authentication), input);
        return ResponseEntity.created(URI.create("/discussions/" + created.slug())).body(created);
    }

    @PostMapping("/rooms/{slug}/join")
    public Map<String, String> join(@PathVariable String slug, Authentication authentication) {
        mutations.joinRoom(principal(authentication), slug);
        return Map.of("message", "You joined the room.");
    }

    @PostMapping("/discussions/{slug}/replies")
    public ResponseEntity<CommunityMutationService.CreatedReply> reply(@PathVariable String slug,
            @Valid @RequestBody CommunityMutationService.CreateReplyRequest input, Authentication authentication) {
        return ResponseEntity.ok(mutations.reply(principal(authentication), slug, input));
    }

    @PostMapping("/discussions/{slug}/vote")
    public Map<String, String> vote(@PathVariable String slug, @Valid @RequestBody CommunityMutationService.VoteRequest input,
                                    Authentication authentication) {
        mutations.vote(principal(authentication), slug, input.value());
        return Map.of("message", "Vote recorded.");
    }

    @PostMapping("/reports")
    public ResponseEntity<Map<String, UUID>> report(@Valid @RequestBody CommunityMutationService.ReportRequest input,
            Authentication authentication, @RequestHeader(value="X-Request-ID", required=false) String requestId) {
        return ResponseEntity.accepted().body(Map.of("id", mutations.report(principal(authentication), input, requestId)));
    }

    private AppPrincipal principal(Authentication authentication) { return (AppPrincipal) authentication.getPrincipal(); }
}
