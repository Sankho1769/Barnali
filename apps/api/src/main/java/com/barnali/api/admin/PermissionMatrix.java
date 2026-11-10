package com.barnali.api.admin;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PermissionMatrix {
    private static final Set<String> OWNER = Set.of(
            "OVERVIEW_READ", "ANALYTICS_READ", "USER_READ", "USER_SUSPEND", "ROOM_READ", "ROOM_MANAGE",
            "REPORT_READ", "REPORT_RESOLVE", "ADMIN_TEAM_READ", "ADMIN_TEAM_MANAGE", "AUDIT_READ");
    private static final Map<AdminRole, Set<String>> PERMISSIONS;

    static {
        Map<AdminRole, Set<String>> map = new EnumMap<>(AdminRole.class);
        map.put(AdminRole.OWNER, OWNER);
        map.put(AdminRole.SUPER_ADMIN, Set.of(
                "OVERVIEW_READ", "ANALYTICS_READ", "USER_READ", "USER_SUSPEND", "ROOM_READ", "ROOM_MANAGE",
                "REPORT_READ", "REPORT_RESOLVE", "ADMIN_TEAM_READ", "AUDIT_READ"));
        map.put(AdminRole.MODERATOR, Set.of(
                "OVERVIEW_READ", "USER_READ", "USER_SUSPEND", "ROOM_READ",
                "REPORT_READ", "REPORT_RESOLVE", "AUDIT_READ"));
        map.put(AdminRole.ANALYTICS_VIEWER, Set.of("OVERVIEW_READ", "ANALYTICS_READ", "ROOM_READ"));
        map.put(AdminRole.SUPPORT_ADMIN, Set.of("OVERVIEW_READ", "USER_READ", "AUDIT_READ"));
        PERMISSIONS = Map.copyOf(map);
    }

    private PermissionMatrix() {}

    public static boolean allows(AdminRole role, String permission) {
        return PERMISSIONS.getOrDefault(role, Set.of()).contains(permission);
    }

    public static List<String> permissions(AdminRole role) {
        return PERMISSIONS.getOrDefault(role, Set.of()).stream().sorted().toList();
    }

    public static List<String> publicPermissions(String role) {
        try {
            return permissions(AdminRole.valueOf(role));
        } catch (IllegalArgumentException exception) {
            return List.of();
        }
    }
}
