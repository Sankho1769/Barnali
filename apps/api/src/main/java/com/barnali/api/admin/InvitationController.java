package com.barnali.api.admin;

import com.barnali.api.security.DatabaseIdentityContext;
import com.barnali.api.config.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/admin-invitations")
public class InvitationController {
    public record AcceptInput(@NotBlank @Size(max=200) String token,
                              @NotBlank @Size(max=80) String displayName,
                              @NotBlank @Pattern(regexp="[A-Za-z0-9_]{3,32}") String username,
                              @NotBlank @Size(min=12,max=72) String password){}
    private final AdminTeamService team;
    public InvitationController(AdminTeamService team){this.team=team;}
    @PostMapping("/accept")
    public Map<String,String> accept(@Valid @RequestBody AcceptInput input){
        try (var ignored = DatabaseIdentityContext.anonymousScope()) {
            team.accept(input.token(),input.displayName(),input.username(),input.password());
            return Map.of("message","Administrator access is ready. Sign in with your account.");
        }
    }
}
