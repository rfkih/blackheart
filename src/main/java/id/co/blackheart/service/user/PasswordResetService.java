package id.co.blackheart.service.user;

import id.co.blackheart.model.PasswordResetToken;
import id.co.blackheart.model.User;
import id.co.blackheart.repository.PasswordResetTokenRepository;
import id.co.blackheart.repository.UserRepository;
import id.co.blackheart.service.email.EmailService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

// Single-use reset tokens with a fixed TTL. Same response for known + unknown
// emails (no enumeration), one live token per user (re-request invalidates
// the prior), one-shot consumption (replays 404), expired tokens 410.
// Token is 32 random bytes, Base64-URL encoded (256 bits of entropy).
@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordResetService {

    private static final int TOKEN_BYTES = 32;
    /** Default TTL when {@code app.password-reset.ttl-minutes} isn't set. */
    private static final long DEFAULT_TTL_MINUTES = 30;
    /** Minimum length we accept for a new password. Matches register-time validation. */
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.password-reset.ttl-minutes:30}")
    private long ttlMinutes;

    /**
     * Public-facing URL the user is told to visit. Defaults to the local
     * Next.js dev server; production deploys override via env var.
     */
    @Value("${app.password-reset.frontend-url:http://localhost:3000/reset-password}")
    private String frontendResetUrl;

    /**
     * Issue a reset token for the user matching {@code email}, if any.
     * Always returns silently — the controller responds 200 with a generic
     * "if an account exists, instructions have been sent" message regardless
     * of whether the email matched a real user.
     */
    @Transactional
    public void requestReset(String email) {
        if (email == null || email.isBlank()) return;
        Optional<User> userOpt = userRepository.findByEmail(email.trim().toLowerCase());
        if (userOpt.isEmpty()) {
            // Don't log the email at INFO+ to avoid leaking enumeration via logs.
            log.debug("Password reset requested for non-existent email");
            return;
        }
        User user = userOpt.get();

        LocalDateTime now = LocalDateTime.now();
        tokenRepository.invalidateActiveForUser(user.getUserId(), now);

        long ttl = ttlMinutes <= 0 ? DEFAULT_TTL_MINUTES : ttlMinutes;
        PasswordResetToken token = PasswordResetToken.builder()
                .token(generateToken())
                .userId(user.getUserId())
                .createdAt(now)
                .expiresAt(now.plusMinutes(ttl))
                .build();
        tokenRepository.save(token);

        String resetUrl = frontendResetUrl + "?token=" + token.getToken();
        long ttlForEmail = ttlMinutes <= 0 ? DEFAULT_TTL_MINUTES : ttlMinutes;
        try {
            emailService.sendPasswordReset(user.getEmail(), resetUrl, ttlForEmail);
        } catch (RuntimeException e) {
            // Send failed — log the URL so ops can deliver it manually.
            // EmailService already logged the exception detail.
            log.warn("PASSWORD RESET TOKEN ISSUED (email send failed) | userId={} expiresAt={} url={}",
                    user.getUserId(), token.getExpiresAt(), resetUrl);
        }
    }

    /**
     * Consume a reset token and update the user's password. Throws on
     * unknown/expired/used tokens; the controller maps those to distinct
     * HTTP statuses so the UX can distinguish error reasons.
     */
    @Transactional
    public void confirmReset(String tokenValue, String newPassword) {
        if (tokenValue == null || tokenValue.isBlank()) {
            throw new EntityNotFoundException("Token not found");
        }
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }

        PasswordResetToken token = tokenRepository.findById(tokenValue)
                .orElseThrow(() -> new EntityNotFoundException("Token not found"));

        if (token.getUsedAt() != null) {
            throw new EntityNotFoundException("Token already used");
        }
        if (token.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new ExpiredResetTokenException("Token has expired");
        }

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new EntityNotFoundException("User not found"));

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        token.setUsedAt(LocalDateTime.now());
        tokenRepository.save(token);

        log.info("Password reset completed | userId={}", user.getUserId());
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Distinguished from EntityNotFoundException so the controller can return 410. */
    public static class ExpiredResetTokenException extends RuntimeException {
        public ExpiredResetTokenException(String message) {
            super(message);
        }
    }
}
