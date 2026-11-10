package com.barnali.api.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/team")
public class AdminTeamController {
    public record InviteInput(@NotBlank @Email @Size(max=254) String email,
                              @NotBlank @Pattern(regexp="SUPER_ADMIN|MODERATOR|ANALYTICS_VIEWER|SUPPORT_ADMIN") String role){}
    public record ReasonInput(@NotBlank @Size(max=600) String reason){}
    public record RoleInput(@NotBlank @Pattern(regexp="SUPER_ADMIN|MODERATOR|ANALYTICS_VIEWER|SUPPORT_ADMIN") String role,
                            @NotBlank @Size(max=600) String reason){}
    public record ActiveInput(boolean active,@NotBlank @Size(max=600) String reason){}

    private final AdminTeamService team;
    private final AdminAuthorization authorization;
    public AdminTeamController(AdminTeamService team,AdminAuthorization authorization){this.team=team;this.authorization=authorization;}

    @GetMapping
    @PreAuthorize("@adminAuthorization.has(authentication, 'ADMIN_TEAM_READ')")
    public TeamResult list(){return new TeamResult(team.admins(),team.invitations());}

    @PostMapping("/invitations")
    @PreAuthorize("@adminAuthorization.isOwner(authentication)")
    public AdminTeamService.CreatedInvitation invite(@Valid @RequestBody InviteInput input,Authentication authentication,
            @RequestHeader(value="X-Request-ID",required=false) String requestId){
        return team.invite(authorization.userId(authentication),input.email(),input.role(),requestId);
    }

    @PostMapping("/invitations/{id}/revoke")
    @PreAuthorize("@adminAuthorization.isOwner(authentication)")
    public Map<String,String> revoke(@PathVariable UUID id,@Valid @RequestBody ReasonInput input,Authentication authentication,
            @RequestHeader(value="X-Request-ID",required=false) String requestId){
        team.revokeInvitation(authorization.userId(authentication),id,input.reason(),requestId);return Map.of("message","Invitation revoked.");
    }

    @PatchMapping("/{id}/role")
    @PreAuthorize("@adminAuthorization.isOwner(authentication)")
    public Map<String,String> role(@PathVariable UUID id,@Valid @RequestBody RoleInput input,Authentication authentication,
            @RequestHeader(value="X-Request-ID",required=false) String requestId){
        team.changeRole(authorization.userId(authentication),id,input.role(),input.reason(),requestId);return Map.of("message","Administrator role updated.");
    }

    @PatchMapping("/{id}/active")
    @PreAuthorize("@adminAuthorization.isOwner(authentication)")
    public Map<String,String> active(@PathVariable UUID id,@Valid @RequestBody ActiveInput input,Authentication authentication,
            @RequestHeader(value="X-Request-ID",required=false) String requestId){
        team.setAdminActive(authorization.userId(authentication),id,input.active(),input.reason(),requestId);return Map.of("message","Administrator access updated.");
    }

    public record TeamResult(List<AdminTeamService.AdminRow> admins,List<AdminTeamService.InvitationRow> invitations){}
}
