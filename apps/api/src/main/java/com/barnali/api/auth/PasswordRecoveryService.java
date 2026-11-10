package com.barnali.api.auth;

import com.barnali.api.config.BarnaliProperties;
import com.barnali.api.config.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordRecoveryService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final ObjectProvider<JavaMailSender> mailSenders;
    private final BarnaliProperties properties;
    private final LoginRateLimitService rateLimits;
    private final AccountService accounts;

    public PasswordRecoveryService(JdbcTemplate jdbc, ObjectProvider<JavaMailSender> mailSenders,
                                  BarnaliProperties properties, LoginRateLimitService rateLimits, AccountService accounts) {
        this.jdbc = jdbc;
        this.mailSenders = mailSenders;
        this.properties = properties;
        this.rateLimits = rateLimits;
        this.accounts = accounts;
    }

    @Transactional
    public void requestReset(String email, String remoteAddress) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        if (rateLimits.isBlocked("recovery", normalizedEmail, remoteAddress)) return;
        rateLimits.recordAttempt("recovery", normalizedEmail, remoteAddress);
        JavaMailSender sender = configuredSender();
        if (sender == null || properties.mailFrom() == null || properties.mailFrom().isBlank()) return;

        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String tokenHash = sha256(token);
        boolean issued = Boolean.TRUE.equals(jdbc.queryForObject("SELECT barnali.issue_password_reset(?,?)", Boolean.class,
                normalizedEmail, tokenHash));
        if (!issued) return;

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(properties.mailFrom());
            message.setTo(normalizedEmail);
            message.setSubject("Reset your BARNALI password");
            message.setText("Use this one-time link within 30 minutes to reset your BARNALI password:\n\n"
                    + properties.passwordResetBaseUrl() + "?token=" + token
                    + "\n\nIf you did not request this, you can ignore this message.");
            sender.send(message);
        } catch (RuntimeException exception) {
            jdbc.queryForObject("SELECT barnali.invalidate_password_reset_delivery(?)", Boolean.class, tokenHash);
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Password reset mail delivery failed; token invalidated.");
        }
    }

    @Transactional
    public void reset(String token, String newPassword, String requestId) {
        AccountService.validatePassword(newPassword);
        String tokenHash = sha256(token);
        if (!accounts.resetPasswordWithToken(tokenHash, newPassword, requestId)) {
            throw ApiException.badRequest("RESET_TOKEN_INVALID", "This recovery link is invalid or expired.");
        }
    }

    public static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private JavaMailSender configuredSender() {
        try {
            return mailSenders.getIfAvailable();
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
