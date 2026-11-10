package com.barnali.api.community;

import com.barnali.api.auth.AppPrincipal;
import com.barnali.api.config.ApiException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.text.Normalizer;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommunityMutationService {
    public record CreateDiscussionRequest(@NotNull UUID roomId, @NotBlank @Size(max=180) String title,
                                         @NotBlank @Size(min=10, max=12000) String body) {}
    public record CreateReplyRequest(@NotBlank @Size(max=8000) String body) {}
    public record VoteRequest(@jakarta.validation.constraints.Min(-1) @jakarta.validation.constraints.Max(1) int value) {}
    public record ReportRequest(@NotBlank @Pattern(regexp="DISCUSSION|REPLY|USER") String targetType,
                                @NotNull UUID targetId,
                                @NotBlank @Pattern(regexp="SPAM|HARASSMENT|MISINFORMATION|OFF_TOPIC|OTHER") String reason,
                                @Size(max=1000) String details) {}
    public record CreatedDiscussion(UUID id, String slug) {}
    public record CreatedReply(UUID id) {}

    private final JdbcTemplate jdbc;
    private final CommunityService community;

    public CommunityMutationService(JdbcTemplate jdbc, CommunityService community) {
        this.jdbc = jdbc;
        this.community = community;
    }

    public java.util.List<RoomSummary> myRooms(AppPrincipal actor) { return community.myRooms(actor); }

    @Transactional
    public CreatedDiscussion createDiscussion(AppPrincipal actor, CreateDiscussionRequest input) {
        RoomSummary room = community.findRoom(input.roomId());
        Boolean member = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM room_memberships WHERE room_id=? AND user_id=?)", Boolean.class,
                room.id(), actor.id());
        if (!Boolean.TRUE.equals(member)) throw ApiException.forbidden("ROOM_MEMBERSHIP_REQUIRED", "Join this room before posting.");
        UUID id = UUID.randomUUID();
        String slug = slug(input.title()) + "-" + id.toString().substring(0, 8);
        jdbc.update("INSERT INTO discussions(id, slug, room_id, author_id, title, body) VALUES (?, ?, ?, ?, ?, ?)",
                id, slug, room.id(), actor.id(), clean(input.title()), input.body().trim());
        event(actor.id(), "DISCUSSION_CREATE");
        return new CreatedDiscussion(id, slug);
    }

    @Transactional
    public void joinRoom(AppPrincipal actor, String slug) {
        RoomSummary room = community.room(slug).room();
        int inserted = jdbc.update("INSERT INTO room_memberships(room_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING", room.id(), actor.id());
        if (inserted > 0) event(actor.id(), "ROOM_JOIN");
    }

    @Transactional
    public CreatedReply reply(AppPrincipal actor, String discussionSlug, CreateReplyRequest input) {
        UUID discussionId = jdbc.query("""
                SELECT d.id FROM discussions d JOIN rooms r ON r.id=d.room_id
                WHERE d.slug=? AND d.status='PUBLISHED' AND r.is_active=TRUE AND r.is_public=TRUE
                """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, discussionSlug.toLowerCase(Locale.ROOT));
        if (discussionId == null) throw ApiException.notFound("Discussion not found.");
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO replies(id, discussion_id, author_id, body) VALUES (?, ?, ?, ?)", id, discussionId, actor.id(), input.body().trim());
        event(actor.id(), "REPLY_CREATE");
        return new CreatedReply(id);
    }

    @Transactional
    public void vote(AppPrincipal actor, String discussionSlug, int value) {
        if (value != -1 && value != 1) throw ApiException.badRequest("INVALID_VOTE", "Vote must be -1 or 1.");
        UUID id = jdbc.query("SELECT id FROM discussions WHERE slug=? AND status='PUBLISHED'", rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                discussionSlug.toLowerCase(Locale.ROOT));
        if (id == null) throw ApiException.notFound("Discussion not found.");
        jdbc.update("""
                INSERT INTO discussion_votes(discussion_id,user_id,value) VALUES (?,?,?)
                ON CONFLICT (discussion_id,user_id) DO UPDATE SET value=EXCLUDED.value, created_at=now()
                """, id, actor.id(), value);
        event(actor.id(), "VOTE");
    }

    @Transactional
    public UUID report(AppPrincipal actor, ReportRequest input, String requestId) {
        ReportSubmission submission = jdbc.query("SELECT * FROM barnali.submit_report(?,?,?,?)", rs -> {
            if (!rs.next()) return null;
            return new ReportSubmission(rs.getString("result"), rs.getObject("created_id", UUID.class));
        },
                input.targetType(), input.targetId(), input.reason(), blankToNull(input.details()));
        if (submission == null || "NOT_FOUND".equals(submission.result())) throw ApiException.notFound("Report target not found.");
        if ("DUPLICATE".equals(submission.result())) throw ApiException.conflict("DUPLICATE_REPORT", "You already have a pending report for this item.");
        if (!"CREATED".equals(submission.result()) || submission.id() == null) throw new IllegalStateException("Report submission was not completed.");
        return submission.id();
    }

    private void event(UUID userId, String type) {
        jdbc.update("INSERT INTO user_activity_events(user_id,event_type) VALUES (?,?)", userId, type);
    }

    static String slug(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        String safe = normalized.replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return safe.isBlank() ? "discussion" : safe.substring(0, Math.min(165, safe.length()));
    }

    private static String clean(String value) { return value.trim().replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", ""); }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : clean(value); }
    private record ReportSubmission(String result, UUID id) {}
}
