package com.barnali.api.admin;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAuditService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AdminAuditService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public void record(UUID actorId, String action, String targetType, String targetId,
                       String outcome, String reason, String requestId, Map<String, ?> context) {
        try {
            String safeContext = mapper.writeValueAsString(context == null ? Map.of() : context);
            jdbc.update("""
                    INSERT INTO admin_audit_events(actor_user_id, action, target_type, target_id, outcome, reason, request_id, context)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    """, actorId, bound(action, 80), bound(targetType, 40), targetId == null ? null : bound(targetId, 100),
                    outcome, reason == null ? null : bound(reason, 600), requestId == null ? null : bound(requestId, 100), safeContext);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize safe audit context.", exception);
        }
    }

    private String bound(String value, int max) {
        return value.replaceAll("[\\p{Cntrl}]", "").substring(0, Math.min(value.replaceAll("[\\p{Cntrl}]", "").length(), max));
    }
}
