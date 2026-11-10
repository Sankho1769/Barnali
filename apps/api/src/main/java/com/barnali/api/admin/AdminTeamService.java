package com.barnali.api.admin;

import com.barnali.api.auth.AppPrincipal;
import com.barnali.api.auth.PasswordPolicy;
import com.barnali.api.config.ApiException;
import com.barnali.api.config.BarnaliProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminTeamService {
    public record AdminRow(UUID userId,String displayName,String email,String username,String role,boolean active,
                           Instant createdAt,Instant lastUsedAt){}
    public record InvitationRow(UUID id,String email,String role,String status,Instant expiresAt,Instant createdAt,String inviterName){}
    public record CreatedInvitation(UUID id,String email,String role,Instant expiresAt,String inviteUrl){}
    public record InviteRequest(String email,String role){}
    public record AcceptInvitation(String token,String displayName,String username,String password){}

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final AdminAuditService audit;
    private final BarnaliProperties properties;
    private final SecureRandom random=new SecureRandom();

    public AdminTeamService(JdbcTemplate jdbc,PasswordEncoder encoder,AdminAuditService audit,BarnaliProperties properties){
        this.jdbc=jdbc;this.encoder=encoder;this.audit=audit;this.properties=properties;
    }

    @Transactional(readOnly = true)
    public List<AdminRow> admins(){
        return jdbc.query("""
                SELECT id,display_name,email,username,role,active,created_at,last_used_at
                FROM barnali.admin_team_rows ORDER BY CASE role WHEN 'OWNER' THEN 0 ELSE 1 END,created_at
                """,(rs,n)->new AdminRow(rs.getObject("id",UUID.class),rs.getString("display_name"),rs.getString("email"),rs.getString("username"),
                rs.getString("role"),rs.getBoolean("active"),rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("last_used_at")==null?null:rs.getTimestamp("last_used_at").toInstant()));
    }

    @Transactional(readOnly = true)
    public List<InvitationRow> invitations(){
        return jdbc.query("""
                SELECT i.id,i.email,i.role,CASE WHEN i.revoked_at IS NOT NULL THEN 'REVOKED'
                         WHEN i.accepted_at IS NOT NULL THEN 'ACCEPTED' WHEN i.expires_at<=now() THEN 'EXPIRED' ELSE 'PENDING' END AS status,
                       i.expires_at,i.created_at,u.display_name AS inviter_name
                FROM admin_invitations i JOIN users u ON u.id=i.invited_by ORDER BY i.created_at DESC LIMIT 100
                """,(rs,n)->new InvitationRow(rs.getObject("id",UUID.class),rs.getString("email"),rs.getString("role"),rs.getString("status"),
                rs.getTimestamp("expires_at").toInstant(),rs.getTimestamp("created_at").toInstant(),rs.getString("inviter_name")));
    }

    @Transactional
    public CreatedInvitation invite(UUID actor,String email,String role,String requestId){
        String normalized=email.trim().toLowerCase(Locale.ROOT);
        AdminRole adminRole;
        try{adminRole=AdminRole.valueOf(role.toUpperCase(Locale.ROOT));}
        catch(IllegalArgumentException e){throw ApiException.badRequest("INVALID_ROLE","Choose a supported administrator role.");}
        if(adminRole==AdminRole.OWNER)throw ApiException.forbidden("OWNER_ROLE_PROTECTED","Owner role cannot be assigned by invitation.");
        byte[] bytes=new byte[32];random.nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String tokenHash=sha256(token);UUID id=UUID.randomUUID();Instant expires=Instant.now().plusSeconds(7*24*60*60L);
        String result=jdbc.queryForObject("SELECT barnali.create_admin_invitation(?,?,?,?,?)",String.class,
                id,normalized,adminRole.name(),tokenHash,expires);
        if("OWNER_IDENTITY_RESERVED".equals(result))throw ApiException.conflict("OWNER_IDENTITY_RESERVED","The owner identity cannot be invited as a delegated administrator.");
        if("ALREADY_ADMIN".equals(result))throw ApiException.conflict("ALREADY_ADMIN","This account already has administrator access.");
        if(!"CREATED".equals(result))throw ApiException.forbidden("ADMIN_TEAM_MANAGE_REQUIRED","Owner permission is required.");
        audit.record(actor,"ADMIN_INVITATION_CREATED","admin_invitation",id.toString(),"SUCCESS",null,requestId,Map.of("role",adminRole.name()));
        return new CreatedInvitation(id,normalized,adminRole.name(),expires,properties.invitationBaseUrl()+"?token="+token);
    }

    @Transactional
    public void revokeInvitation(UUID actor,UUID id,String reason,String requestId){
        int updated=jdbc.update("UPDATE admin_invitations SET revoked_at=now() WHERE id=? AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at>now()",id);
        if(updated==0)throw ApiException.conflict("INVITATION_NOT_PENDING","Invitation is already accepted, expired, or revoked.");
        audit.record(actor,"ADMIN_INVITATION_REVOKED","admin_invitation",id.toString(),"SUCCESS",reason,requestId,Map.of());
    }

    @Transactional
    public void changeRole(UUID actor,UUID target,String role,String reason,String requestId){
        AdminRole newRole;
        try{newRole=AdminRole.valueOf(role.toUpperCase(Locale.ROOT));}catch(IllegalArgumentException e){throw ApiException.badRequest("INVALID_ROLE","Choose a supported administrator role.");}
        if(newRole==AdminRole.OWNER)throw ApiException.forbidden("OWNER_ROLE_PROTECTED","The owner role cannot be assigned through team management.");
        UUID ownerId=jdbc.query("SELECT user_id FROM admin_accounts WHERE role='OWNER' AND active=TRUE",rs->rs.next()?rs.getObject(1,UUID.class):null);
        if(target.equals(ownerId))throw ApiException.forbidden("OWNER_PROTECTED","Owner access cannot be changed here.");
        int changed=jdbc.update("UPDATE admin_accounts SET role=? WHERE user_id=? AND user_id<>?",newRole.name(),target,actor);
        if(changed==0){boolean exists=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM admin_accounts WHERE user_id=?)",Boolean.class,target));
            if(!exists)throw ApiException.notFound("Administrator not found.");
            throw ApiException.forbidden("SELF_ROLE_CHANGE","You cannot change your own administrator role.");}
        jdbc.update("UPDATE users SET auth_version=auth_version+1,updated_at=now() WHERE id=?",target);
        audit.record(actor,"ADMIN_ROLE_CHANGED","admin",target.toString(),"SUCCESS",reason,requestId,Map.of("role",newRole.name()));
    }

    @Transactional
    public void setAdminActive(UUID actor,UUID target,boolean active,String reason,String requestId){
        UUID ownerId=jdbc.query("SELECT user_id FROM admin_accounts WHERE role='OWNER' AND active=TRUE",rs->rs.next()?rs.getObject(1,UUID.class):null);
        if(target.equals(ownerId))throw ApiException.forbidden("OWNER_PROTECTED","Owner access cannot be suspended here.");
        if(target.equals(actor))throw ApiException.forbidden("SELF_ACCESS_CHANGE","You cannot revoke your own administrator access.");
        int changed=jdbc.update("UPDATE admin_accounts SET active=? WHERE user_id=?",active,target);
        if(changed==0)throw ApiException.notFound("Administrator not found.");
        jdbc.update("UPDATE users SET auth_version=auth_version+1,updated_at=now() WHERE id=?",target);
        audit.record(actor,active?"ADMIN_ACCESS_RESTORED":"ADMIN_ACCESS_REVOKED","admin",target.toString(),"SUCCESS",reason,requestId,Map.of());
    }

    @Transactional
    public void accept(String token,String displayName,String username,String password){
        if(token==null||token.length()<32||token.length()>200)throw ApiException.badRequest("INVALID_INVITATION","Invitation is invalid or expired.");
        String tokenHash=sha256(token);
        var invitation=jdbc.query("SELECT * FROM barnali.invitation_by_token(?)",
                rs->rs.next()?new InvitationData(rs.getString("email"),rs.getString("role")):null,tokenHash);
        if(invitation==null)throw ApiException.badRequest("INVALID_INVITATION","Invitation is invalid or expired.");
        PasswordPolicy.validate(password);
        AppPrincipal existing=jdbc.query("SELECT * FROM barnali.auth_principal_by_email(?)",(rs,n)->new AppPrincipal(
                rs.getObject("id",UUID.class),rs.getString("email"),rs.getString("username"),rs.getString("display_name"),
                rs.getString("password_hash"),rs.getString("status"),rs.getBoolean("must_change_password"),rs.getLong("auth_version")),
                invitation.email()).stream().findFirst().orElse(null);
        String normalizedUsername=username==null?"":username.trim().toLowerCase(Locale.ROOT);
        String display=displayName==null?"":displayName.trim();
        UUID existingUserId=null;
        String newPasswordHash=null;
        if(existing==null){
            if(!normalizedUsername.matches("[a-z0-9_]{3,32}"))throw ApiException.badRequest("INVALID_USERNAME","Username must contain 3 to 32 letters, numbers, or underscores.");
            if(display.isBlank()||display.length()>80)throw ApiException.badRequest("INVALID_DISPLAY_NAME","Display name is required and must be at most 80 characters.");
            newPasswordHash=encoder.encode(password);
        } else {
            if(!"ACTIVE".equals(existing.status()))throw ApiException.forbidden("ACCOUNT_UNAVAILABLE","This account is not available for administrator activation.");
            if(!encoder.matches(password,existing.passwordHash()))throw ApiException.unauthorized("Use the password already associated with this account to accept the invitation.");
            existingUserId=existing.id();
        }
        Boolean accepted=jdbc.queryForObject("SELECT barnali.accept_invitation(?,?,?,?,?)",Boolean.class,
                tokenHash,existingUserId,display,normalizedUsername,newPasswordHash);
        if(!Boolean.TRUE.equals(accepted))throw ApiException.badRequest("INVALID_INVITATION","Invitation is invalid or expired.");
    }

    private static String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException("SHA-256 unavailable",e);}}
    private record InvitationData(String email,String role){}
}
