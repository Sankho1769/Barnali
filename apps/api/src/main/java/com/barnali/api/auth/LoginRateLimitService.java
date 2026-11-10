package com.barnali.api.auth;

import com.barnali.api.config.BarnaliProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoginRateLimitService {
    private final JdbcTemplate jdbc;
    private final byte[] hmacKey;

    public LoginRateLimitService(JdbcTemplate jdbc, BarnaliProperties properties) {
        this.jdbc = jdbc;
        String configuredKey = properties.securityHmacKey() == null ? "" : properties.securityHmacKey();
        if (configuredKey.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("APP_SECURITY_HMAC_KEY must be set to a random value of at least 32 bytes.");
        }
        this.hmacKey = configuredKey.getBytes(StandardCharsets.UTF_8);
    }

    @Transactional(readOnly = true)
    public boolean isBlocked(String purpose, String identity, String remoteAddress) {
        String identityHash = subjectHash(purpose, "identity", identity, remoteAddress);
        String addressHash = subjectHash(purpose, "address", "", remoteAddress);
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT barnali.login_limit_blocked(?,?)", Boolean.class,
                identityHash, addressHash));
    }

    @Transactional
    public void recordAttempt(String purpose, String identity, String remoteAddress) {
        jdbc.queryForObject("SELECT barnali.login_limit_record_attempt(?,?)", Boolean.class,
                subjectHash(purpose, "identity", identity, remoteAddress),
                subjectHash(purpose, "address", "", remoteAddress));
    }

    @Transactional
    public void clear(String purpose, String identity, String remoteAddress) {
        jdbc.queryForObject("SELECT barnali.login_limit_clear(?)", Boolean.class,
                subjectHash(purpose, "identity", identity, remoteAddress));
    }

    @Transactional
    public boolean shouldAuditBlockedAddress(String purpose, String remoteAddress) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT barnali.login_limit_audit_address(?)", Boolean.class,
                subjectHash(purpose,"address","",remoteAddress)));
    }

    @Scheduled(fixedDelay = 3_600_000L)
    @Transactional
    void removeOldBuckets() {
        jdbc.queryForObject("SELECT barnali.login_limit_prune()", Integer.class);
    }

    private String subjectHash(String purpose, String dimension, String identity, String remoteAddress) {
        String source = purpose + "\n" + dimension + "\n" + (identity == null ? "" : identity.trim().toLowerCase()) + "\n" + (remoteAddress == null ? "unknown" : remoteAddress);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(source.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC is unavailable.", exception);
        }
    }
}
