package com.barnali.api.admin;

import com.barnali.api.auth.AppPrincipal;
import com.barnali.api.community.PageResult;
import com.barnali.api.config.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminService {
    public record Overview(long totalUsers, long newUsers, long activeUsers, long dau, long wau, long mau,
                           long totalRooms, long totalDiscussions, long totalReplies, long pendingReports,
                           long suspendedUsers, String from, String to, Instant refreshedAt) {}
    public record TimePoint(String date, long value) {}
    public record Analytics(Overview overview, List<TimePoint> registrations, List<TimePoint> activeUsers,
                            List<TimePoint> engagement) {}
    public record UserRow(UUID id, String username, String displayName, String email, String status,
                          boolean emailVerified, Instant createdAt, Instant lastActivityAt, String role,
                          long roomCount, long discussionCount, long replyCount) {}
    public record UserDetail(UserRow user, List<TimePoint> activity) {}
    public record ReportRow(UUID id, String targetType, UUID targetId, String reason, String details,
                            String status, Instant createdAt, String reporterName, String targetSummary) {}
    public record AuditRow(long id, String actorName, String action, String targetType, String targetId,
                           String outcome, String reason, Instant createdAt) {}
    public record ReportAction(String action, String reason) {}
    public record RoomInput(String name, String description) {}
    public record RoomAdminRow(UUID id, String slug, String name, String description, boolean active,
                               boolean publicRoom, long memberCount, long discussionCount, Instant createdAt) {}

    private final JdbcTemplate jdbc;
    private final AdminAuditService audit;

    public AdminService(JdbcTemplate jdbc, AdminAuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Analytics analytics(LocalDate from, LocalDate to) {
        DateRange range = dateRange(from, to);
        Overview overview = calculateOverview(range, true);
        return new Analytics(overview, series(range.from(), range.to(), "registrations"),
                series(range.from(), range.to(), "active"), series(range.from(), range.to(), "engagement"));
    }

    @Transactional(readOnly = true)
    public Overview overview(LocalDate from, LocalDate to) { return calculateOverview(dateRange(from,to), false); }

    private Overview calculateOverview(DateRange range, boolean analyticsPermission) {
        String function = analyticsPermission ? "admin_analytics_overview" : "admin_overview";
        OverviewCounts counts = jdbc.queryForObject("SELECT * FROM barnali." + function + "(?,?)",
                (rs, row) -> new OverviewCounts(rs.getLong("total_users"),rs.getLong("new_users"),rs.getLong("active_users"),
                        rs.getLong("dau"),rs.getLong("wau"),rs.getLong("mau"),rs.getLong("total_rooms"),
                        rs.getLong("total_discussions"),rs.getLong("total_replies"),rs.getLong("pending_reports"),
                        rs.getLong("suspended_users")), range.from(), range.to());
        return new Overview(counts.users(), counts.newUsers(), counts.active(), counts.dau(), counts.wau(), counts.mau(),
                counts.rooms(), counts.discussions(), counts.replies(), counts.reports(), counts.suspended(),
                range.from().toString(), range.to().toString(), Instant.now());
    }

    @Transactional(readOnly = true)
    public PageResult<UserRow> users(String query, String status, int page, int size, String sort) {
        page(page, size);
        if (status != null && !List.of("ACTIVE", "SUSPENDED").contains(status.toUpperCase(Locale.ROOT))) {
            throw ApiException.badRequest("INVALID_STATUS", "Status must be ACTIVE or SUSPENDED.");
        }
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            where.append(" AND (u.username ILIKE ? OR u.display_name ILIKE ? OR u.email ILIKE ?)");
            String term = "%" + query.trim().substring(0, Math.min(query.trim().length(), 100)) + "%";
            args.add(term); args.add(term); args.add(term);
        }
        if (status != null && !status.isBlank()) { where.append(" AND u.status=?"); args.add(status.toUpperCase(Locale.ROOT)); }
        long total = jdbc.queryForObject("SELECT count(*) FROM barnali.admin_user_rows u" + where, Long.class, args.toArray());
        String order = switch (sort == null ? "created" : sort) {
            case "name" -> "u.display_name ASC, u.id ASC";
            case "activity" -> "last_activity_at DESC NULLS LAST, u.id ASC";
            default -> "u.created_at DESC, u.id DESC";
        };
        args.add(size); args.add(page * size);
        List<UserRow> items = jdbc.query("SELECT u.id,u.username,u.display_name,u.email,u.status,u.email_verified,"
                + "u.created_at,u.last_activity_at,u.role,u.room_count,u.discussion_count,u.reply_count "
                + "FROM barnali.admin_user_rows u" + where + " ORDER BY " + order + " LIMIT ? OFFSET ?",
                (rs, row) -> userRow(rs), args.toArray());
        return new PageResult<>(items, page, size, total, (int)Math.ceil(total / (double)size));
    }

    @Transactional(readOnly = true)
    public UserDetail user(UUID id) {
        UserRow row = jdbc.query("SELECT id,username,display_name,email,status,email_verified,created_at,last_activity_at,role,"
                + "room_count,discussion_count,reply_count FROM barnali.admin_user_rows WHERE id=?",
                rs -> rs.next() ? userRow(rs) : null, id);
        if (row == null) throw ApiException.notFound("User not found.");
        LocalDate to = LocalDate.now(ZoneOffset.UTC), from = to.minusDays(29);
        List<TimePoint> activity = jdbc.query("SELECT day,value FROM barnali.user_activity_series(?,?,?) ORDER BY day",
                (rs, n) -> new TimePoint(rs.getDate("day").toLocalDate().toString(), rs.getLong("value")), id, from, to);
        return new UserDetail(row, activity);
    }

    @Transactional
    public UserRow setUserStatus(UUID actor, UUID userId, String status, String reason, String requestId) {
        if (!"ACTIVE".equals(status) && !"SUSPENDED".equals(status)) throw ApiException.badRequest("INVALID_STATUS", "Status must be ACTIVE or SUSPENDED.");
        if (actor.equals(userId)) throw ApiException.forbidden("SELF_MODERATION", "You cannot suspend or reinstate your own account.");
        String role = jdbc.query("SELECT role FROM admin_accounts WHERE user_id=?", rs -> rs.next() ? rs.getString(1) : null, userId);
        if (role != null) throw ApiException.forbidden("ADMIN_ACCOUNT_PROTECTED", "Administrative access must be changed through owner-controlled team management.");
        int changed = jdbc.update("UPDATE users SET status=?,auth_version=auth_version+1,updated_at=now() WHERE id=? AND status<>?", status, userId, status);
        if (changed == 0 && !existsUser(userId)) throw ApiException.notFound("User not found.");
        audit.record(actor, "USER_" + status, "user", userId.toString(), "SUCCESS", reason, requestId, Map.of());
        return user(userId).user();
    }

    @Transactional(readOnly = true)
    public PageResult<ReportRow> reports(String status, int page, int size) {
        page(page, size);
        String filter = status == null || status.isBlank() ? "PENDING" : status.toUpperCase(Locale.ROOT);
        if (!List.of("PENDING", "RESOLVED", "DISMISSED", "ALL").contains(filter)) throw ApiException.badRequest("INVALID_STATUS", "Use PENDING, RESOLVED, DISMISSED, or ALL.");
        String where = "ALL".equals(filter) ? "" : " WHERE rp.status=?";
        Object[] filterArgs = "ALL".equals(filter) ? new Object[0] : new Object[]{filter};
        long total = jdbc.queryForObject("SELECT count(*) FROM reports rp" + where, Long.class, filterArgs);
        List<Object> args = new ArrayList<>(List.of(filterArgs)); args.add(size); args.add(page * size);
        List<ReportRow> items = jdbc.query("""
                SELECT rp.id,rp.target_type,rp.target_id,rp.reason,rp.details,rp.status,rp.created_at,
                       u.display_name AS reporter_name,
                       CASE WHEN rp.target_type='DISCUSSION' THEN (SELECT title FROM discussions WHERE id=rp.target_id)
                            WHEN rp.target_type='REPLY' THEN (SELECT left(body,180) FROM replies WHERE id=rp.target_id)
                            WHEN rp.target_type='USER' THEN (SELECT display_name FROM users WHERE id=rp.target_id) END AS target_summary
                FROM reports rp JOIN users u ON u.id=rp.reporter_id
                """ + where + " ORDER BY rp.created_at DESC,rp.id DESC LIMIT ? OFFSET ?", (rs, n) -> new ReportRow(
                rs.getObject("id", UUID.class), rs.getString("target_type"), rs.getObject("target_id", UUID.class), rs.getString("reason"),
                rs.getString("details"), rs.getString("status"), instant(rs.getTimestamp("created_at")), rs.getString("reporter_name"),
                rs.getString("target_summary")), args.toArray());
        return new PageResult<>(items, page, size, total, (int)Math.ceil(total/(double)size));
    }

    @Transactional
    public void resolveReport(UUID actor, UUID reportId, ReportAction action, String requestId) {
        if (action.reason() == null || action.reason().isBlank()) throw ApiException.badRequest("REASON_REQUIRED", "A resolution reason is required.");
        var report = jdbc.query("SELECT target_type,target_id,status FROM reports WHERE id=? FOR UPDATE", rs -> rs.next()
                ? new ReportRow(reportId,rs.getString(1),rs.getObject(2,UUID.class),null,null,rs.getString(3),null,null,null) : null, reportId);
        if (report == null) throw ApiException.notFound("Report not found.");
        if (!"PENDING".equals(report.status())) throw ApiException.conflict("REPORT_ALREADY_RESOLVED", "This report is no longer pending.");
        if ("HIDE_TARGET".equals(action.action())) {
            switch (report.targetType()) {
                case "DISCUSSION" -> jdbc.update("UPDATE discussions SET status='HIDDEN',updated_at=now() WHERE id=?", report.targetId());
                case "REPLY" -> jdbc.update("UPDATE replies SET status='HIDDEN' WHERE id=?", report.targetId());
                default -> throw ApiException.badRequest("INVALID_ACTION", "Choose dismiss for this report type.");
            }
        } else if (!"DISMISS".equals(action.action())) throw ApiException.badRequest("INVALID_ACTION", "Action must be DISMISS or HIDE_TARGET.");
        String status = "DISMISS".equals(action.action()) ? "DISMISSED" : "RESOLVED";
        jdbc.update("UPDATE reports SET status=?,reviewer_id=?,resolution=?,resolved_at=now() WHERE id=?", status, actor,
                action.reason().trim().substring(0, Math.min(600, action.reason().trim().length())), reportId);
        audit.record(actor, "REPORT_" + status, "report", reportId.toString(), "SUCCESS", action.reason(), requestId,
                Map.of("targetType", report.targetType(), "targetId", report.targetId().toString()));
    }

    @Transactional(readOnly = true)
    public PageResult<AuditRow> auditLog(String action, int page, int size) {
        page(page, size);
        String where = action == null || action.isBlank() ? "" : " WHERE e.action=?";
        Object[] filterArgs = where.isEmpty() ? new Object[0] : new Object[]{action.toUpperCase(Locale.ROOT)};
        long total = jdbc.queryForObject("SELECT count(*) FROM barnali.admin_audit_rows e" + where, Long.class, filterArgs);
        List<Object> args = new ArrayList<>(List.of(filterArgs)); args.add(size); args.add(page * size);
        List<AuditRow> rows = jdbc.query("""
                SELECT e.id,e.actor_name,e.action,e.target_type,e.target_id,e.outcome,e.reason,e.created_at
                FROM barnali.admin_audit_rows e
                """ + where + " ORDER BY e.created_at DESC,e.id DESC LIMIT ? OFFSET ?", (rs, n) -> new AuditRow(
                rs.getLong("id"),rs.getString("actor_name"),rs.getString("action"),rs.getString("target_type"),rs.getString("target_id"),
                rs.getString("outcome"),rs.getString("reason"),instant(rs.getTimestamp("created_at"))), args.toArray());
        return new PageResult<>(rows,page,size,total,(int)Math.ceil(total/(double)size));
    }

    @Transactional(readOnly = true)
    public List<RoomAdminRow> rooms() {
        return jdbc.query("""
                SELECT id,slug,name,description,is_active,is_public,created_at,member_count,discussion_count
                FROM barnali.admin_room_rows ORDER BY created_at DESC
                """, (rs,n)->new RoomAdminRow(rs.getObject("id",UUID.class),rs.getString("slug"),rs.getString("name"),rs.getString("description"),
                rs.getBoolean("is_active"),rs.getBoolean("is_public"),rs.getLong("member_count"),rs.getLong("discussion_count"),instant(rs.getTimestamp("created_at"))));
    }

    @Transactional
    public RoomAdminRow createRoom(UUID actor, RoomInput input, String requestId) {
        if (input.name()==null || input.name().isBlank() || input.name().length()>120 || input.description()==null || input.description().isBlank() || input.description().length()>600)
            throw ApiException.badRequest("INVALID_ROOM", "Room name (1–120) and description (1–600) are required.");
        UUID id=UUID.randomUUID(); String slug=slug(input.name())+"-"+id.toString().substring(0,6);
        jdbc.update("INSERT INTO rooms(id,slug,name,description,created_by) VALUES (?,?,?,?,?)",id,slug,input.name().trim(),input.description().trim(),actor);
        audit.record(actor,"ROOM_CREATE","room",id.toString(),"SUCCESS",null,requestId,Map.of());
        return rooms().stream().filter(room->room.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public void setRoomActive(UUID actor, UUID id, boolean active, String requestId) {
        int count=jdbc.update("UPDATE rooms SET is_active=?,updated_at=now() WHERE id=?",active,id);
        if(count==0) throw ApiException.notFound("Room not found.");
        audit.record(actor,active?"ROOM_ACTIVATE":"ROOM_DEACTIVATE","room",id.toString(),"SUCCESS",null,requestId,Map.of());
    }

    private List<TimePoint> series(LocalDate from,LocalDate to,String type) {
        return jdbc.query("SELECT day,value FROM barnali.admin_analytics_series(?,?,?) ORDER BY day",
                (rs,n)->new TimePoint(rs.getDate("day").toLocalDate().toString(),rs.getLong("value")),from,to,type);
    }

    private DateRange dateRange(LocalDate from,LocalDate to) {
        LocalDate today=LocalDate.now(ZoneOffset.UTC);
        LocalDate end=to==null?today:to;
        LocalDate start=from==null?end.minusDays(29):from;
        if(start.isAfter(end)||end.isAfter(today)||start.isBefore(end.minusDays(364)))
            throw ApiException.badRequest("INVALID_DATE_RANGE","Choose a UTC date range ending today or earlier and spanning at most 365 days.");
        return new DateRange(start,end);
    }
    private boolean existsUser(UUID id){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM users WHERE id=?)",Boolean.class,id));}
    private void page(int page,int size){if(page<0||page>100000||size<1||size>100)throw ApiException.badRequest("INVALID_PAGE","Use a page from 0 to 100000 and a size from 1 to 100.");}
    private UserRow userRow(java.sql.ResultSet rs)throws java.sql.SQLException{return new UserRow(rs.getObject("id",UUID.class),rs.getString("username"),rs.getString("display_name"),rs.getString("email"),rs.getString("status"),rs.getBoolean("email_verified"),instant(rs.getTimestamp("created_at")),instant(rs.getTimestamp("last_activity_at")),rs.getString("role"),rs.getLong("room_count"),rs.getLong("discussion_count"),rs.getLong("reply_count"));}
    private static Instant instant(Timestamp t){return t==null?null:t.toInstant();}
    private static String slug(String value){String safe=value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","-").replaceAll("^-|-$","");return safe.isBlank()?"room":safe.substring(0,Math.min(110,safe.length()));}
    private record DateRange(LocalDate from,LocalDate to){}
    private record OverviewCounts(long users,long newUsers,long active,long dau,long wau,long mau,long rooms,
                                  long discussions,long replies,long reports,long suspended){}
}
