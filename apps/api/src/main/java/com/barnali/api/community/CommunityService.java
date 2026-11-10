package com.barnali.api.community;

import com.barnali.api.auth.AppPrincipal;
import com.barnali.api.config.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommunityService {
    private static final String DISCUSSION_FROM = """
            FROM discussions d
            JOIN rooms r ON r.id = d.room_id
            JOIN users u ON u.id = d.author_id
            WHERE d.status = 'PUBLISHED' AND r.is_active = TRUE AND r.is_public = TRUE AND u.status = 'ACTIVE'
            """;

    private final JdbcTemplate jdbc;

    public CommunityService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PageResult<DiscussionSummary> discussions(int page, int size, String query, String sort, String roomSlug) {
        validatePage(page, size);
        StringBuilder where = new StringBuilder();
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        if (query != null && !query.isBlank()) {
            where.append(" AND to_tsvector('simple', d.title || ' ' || d.body) @@ plainto_tsquery('simple', ?)");
            args.add(query.trim().substring(0, Math.min(query.trim().length(), 120)));
        }
        if (roomSlug != null && !roomSlug.isBlank()) {
            where.append(" AND r.slug = ?");
            args.add(roomSlug.toLowerCase(Locale.ROOT));
        }
        Long total = jdbc.queryForObject("SELECT count(*)" + DISCUSSION_FROM + where, Long.class, args.toArray());
        String order = "popular".equalsIgnoreCase(sort)
                ? " ORDER BY vote_count DESC, d.published_at DESC, d.id DESC"
                : " ORDER BY d.published_at DESC, d.id DESC";
        args.add(size);
        args.add(page * size);
        List<DiscussionSummary> items = jdbc.query(
                "SELECT d.id, d.slug, d.title, left(d.body, 320) AS excerpt, d.created_at, r.name AS room_name, r.slug AS room_slug, " +
                        "u.display_name AS author_name, u.username AS author_username, " +
                        "(SELECT count(*) FROM replies rp WHERE rp.discussion_id=d.id AND rp.status='PUBLISHED') AS reply_count, " +
                        "barnali.discussion_vote_total(d.id) AS vote_count " +
                        DISCUSSION_FROM + where + order + " LIMIT ? OFFSET ?",
                (rs, row) -> new DiscussionSummary(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("title"),
                        rs.getString("excerpt"), instant(rs.getTimestamp("created_at")), rs.getString("room_name"),
                        rs.getString("room_slug"), rs.getString("author_name"), rs.getString("author_username"),
                        rs.getLong("reply_count"), rs.getLong("vote_count")), args.toArray());
        return new PageResult<>(items, page, size, total == null ? 0 : total, (int) Math.ceil((total == null ? 0 : total) / (double) size));
    }

    public List<RoomSummary> rooms(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 20));
        return jdbc.query("""
                SELECT r.id, r.slug, r.name, r.description, r.member_count, r.discussion_count
                FROM barnali.public_room_summaries r
                ORDER BY discussion_count DESC, r.name ASC LIMIT ?
                """, (rs, row) -> new RoomSummary(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("name"),
                rs.getString("description"), rs.getLong("member_count"), rs.getLong("discussion_count")), safeLimit);
    }

    @Transactional(readOnly = true)
    public List<RoomSummary> myRooms(AppPrincipal principal) {
        return jdbc.query("""
                SELECT r.id,r.slug,r.name,r.description,r.member_count,r.discussion_count
                FROM room_memberships own JOIN barnali.public_room_summaries r ON r.id=own.room_id
                WHERE own.user_id=? ORDER BY r.name
                """,(rs,n)->new RoomSummary(rs.getObject("id",UUID.class),rs.getString("slug"),rs.getString("name"),rs.getString("description"),
                rs.getLong("member_count"),rs.getLong("discussion_count")),principal.id());
    }

    public RoomDetail room(String slug) {
        RoomSummary room = jdbc.query("""
                SELECT r.id,r.slug,r.name,r.description,r.member_count,r.discussion_count
                FROM barnali.public_room_summaries r WHERE r.slug = ?
                """, rs -> rs.next() ? new RoomSummary(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("name"),
                rs.getString("description"), rs.getLong("member_count"), rs.getLong("discussion_count")) : null, slug.toLowerCase(Locale.ROOT));
        if (room == null) throw ApiException.notFound("Room not found.");
        return new RoomDetail(room, discussions(0, 30, null, "latest", room.slug()));
    }

    public DiscussionDetail discussion(String slug) {
        DiscussionDetail base = jdbc.query("""
                SELECT d.id, d.slug, d.title, d.body, d.created_at, d.room_id, r.name AS room_name, r.slug AS room_slug,
                       u.id AS author_id, u.display_name AS author_name, u.username AS author_username,
                       (SELECT count(*) FROM replies rp WHERE rp.discussion_id=d.id AND rp.status='PUBLISHED') AS reply_count,
                       barnali.discussion_vote_total(d.id) AS vote_count
                FROM discussions d JOIN rooms r ON r.id=d.room_id JOIN users u ON u.id=d.author_id
                WHERE d.slug=? AND d.status='PUBLISHED' AND r.is_active=TRUE AND r.is_public=TRUE AND u.status='ACTIVE'
                """, rs -> rs.next() ? new DiscussionDetail(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("title"),
                excerpt(rs.getString("body")), rs.getString("body"), instant(rs.getTimestamp("created_at")), rs.getObject("room_id", UUID.class),
                rs.getString("room_name"), rs.getString("room_slug"), rs.getObject("author_id", UUID.class), rs.getString("author_name"),
                rs.getString("author_username"), rs.getLong("reply_count"), rs.getLong("vote_count"), List.of()) : null, slug.toLowerCase(Locale.ROOT));
        if (base == null) throw ApiException.notFound("Discussion not found.");
        List<DiscussionDetail.ReplySummary> replies = jdbc.query("""
                SELECT rp.id, rp.body, rp.created_at, u.display_name AS author_name
                FROM replies rp JOIN users u ON u.id=rp.author_id
                WHERE rp.discussion_id=? AND rp.status='PUBLISHED' AND u.status='ACTIVE'
                ORDER BY rp.created_at ASC LIMIT 500
                """, (rs, row) -> new DiscussionDetail.ReplySummary(rs.getObject("id", UUID.class), rs.getString("body"),
                rs.getString("author_name"), instant(rs.getTimestamp("created_at"))), base.id());
        return new DiscussionDetail(base.id(), base.slug(), base.title(), base.excerpt(), base.body(), base.createdAt(), base.roomId(),
                base.roomName(), base.roomSlug(), base.authorId(), base.authorName(), base.authorUsername(), base.replyCount(), base.voteCount(), replies);
    }

    public RoomSummary findRoom(UUID roomId) {
        RoomSummary result = jdbc.query("""
                SELECT r.id,r.slug,r.name,r.description,r.member_count,r.discussion_count
                FROM barnali.public_room_summaries r WHERE r.id=?
                """, rs -> rs.next() ? new RoomSummary(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("name"),
                rs.getString("description"), rs.getLong("member_count"), rs.getLong("discussion_count")) : null, roomId);
        if (result == null) throw ApiException.notFound("Room not found.");
        return result;
    }

    static void validatePage(int page, int size) {
        if (page < 0 || page > 100_000 || size < 1 || size > 50) throw ApiException.badRequest("INVALID_PAGE", "Use a page from 0 to 100000 and a size from 1 to 50.");
    }

    static Instant instant(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }
    private static String excerpt(String body) { return body.length() <= 320 ? body : body.substring(0, 317) + "…"; }
}
