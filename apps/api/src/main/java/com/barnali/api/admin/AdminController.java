package com.barnali.api.admin;

import com.barnali.api.auth.AppPrincipal;
import com.barnali.api.community.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    public record UserStatusRequest(@NotBlank @Pattern(regexp="ACTIVE|SUSPENDED") String status,
                                    @NotBlank @Size(max=600) String reason) {}
    public record RoomActiveRequest(boolean active) {}

    private final AdminService admin;
    private final AdminAuthorization authorization;

    public AdminController(AdminService admin, AdminAuthorization authorization) {
        this.admin=admin; this.authorization=authorization;
    }

    @GetMapping("/analytics")
    @PreAuthorize("@adminAuthorization.has(authentication, 'ANALYTICS_READ')")
    public AdminService.Analytics analytics(@RequestParam(required=false) LocalDate from,
                                            @RequestParam(required=false) LocalDate to) { return admin.analytics(from,to); }

    @GetMapping("/overview")
    @PreAuthorize("@adminAuthorization.has(authentication, 'OVERVIEW_READ')")
    public AdminService.Overview overview(@RequestParam(required=false) LocalDate from,
                                          @RequestParam(required=false) LocalDate to) { return admin.overview(from,to); }

    @GetMapping("/users")
    @PreAuthorize("@adminAuthorization.has(authentication, 'USER_READ')")
    public PageResult<AdminService.UserRow> users(@RequestParam(required=false) String q,
            @RequestParam(required=false) String status,@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="25") int size,@RequestParam(defaultValue="created") String sort) {
        return admin.users(q,status,page,size,sort);
    }

    @GetMapping("/users/{id}")
    @PreAuthorize("@adminAuthorization.has(authentication, 'USER_READ')")
    public AdminService.UserDetail user(@PathVariable UUID id){return admin.user(id);}

    @PatchMapping("/users/{id}/status")
    @PreAuthorize("@adminAuthorization.has(authentication, 'USER_SUSPEND')")
    public AdminService.UserRow userStatus(@PathVariable UUID id,@Valid @RequestBody UserStatusRequest input,
            Authentication authentication,@RequestHeader(value="X-Request-ID",required=false) String requestId) {
        return admin.setUserStatus(actor(authentication),id,input.status(),input.reason(),requestId);
    }

    @GetMapping("/reports")
    @PreAuthorize("@adminAuthorization.has(authentication, 'REPORT_READ')")
    public PageResult<AdminService.ReportRow> reports(@RequestParam(defaultValue="PENDING") String status,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){return admin.reports(status,page,size);}

    @PostMapping("/reports/{id}/resolve")
    @PreAuthorize("@adminAuthorization.has(authentication, 'REPORT_RESOLVE')")
    public Map<String,String> resolve(@PathVariable UUID id,@Valid @RequestBody AdminService.ReportAction input,
            Authentication authentication,@RequestHeader(value="X-Request-ID",required=false) String requestId){
        admin.resolveReport(actor(authentication),id,input,requestId);return Map.of("message","Report updated.");
    }

    @GetMapping("/audit")
    @PreAuthorize("@adminAuthorization.has(authentication, 'AUDIT_READ')")
    public PageResult<AdminService.AuditRow> audit(@RequestParam(required=false) String action,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size){return admin.auditLog(action,page,size);}

    @GetMapping("/rooms")
    @PreAuthorize("@adminAuthorization.has(authentication, 'ROOM_READ')")
    public List<AdminService.RoomAdminRow> rooms(){return admin.rooms();}

    @PostMapping("/rooms")
    @PreAuthorize("@adminAuthorization.has(authentication, 'ROOM_MANAGE')")
    public AdminService.RoomAdminRow createRoom(@Valid @RequestBody RoomCreateRequest input,Authentication authentication,
            @RequestHeader(value="X-Request-ID",required=false) String requestId){
        return admin.createRoom(actor(authentication),new AdminService.RoomInput(input.name(),input.description()),requestId);
    }

    @PatchMapping("/rooms/{id}/active")
    @PreAuthorize("@adminAuthorization.has(authentication, 'ROOM_MANAGE')")
    public Map<String,String> setRoomActive(@PathVariable UUID id,@RequestBody RoomActiveRequest input,Authentication authentication,
            @RequestHeader(value="X-Request-ID",required=false) String requestId){
        admin.setRoomActive(actor(authentication),id,input.active(),requestId);return Map.of("message","Room status updated.");
    }

    public record RoomCreateRequest(@NotBlank @Size(max=120) String name,@NotBlank @Size(max=600) String description){}
    private UUID actor(Authentication authentication){return authorization.userId(authentication);}
}
