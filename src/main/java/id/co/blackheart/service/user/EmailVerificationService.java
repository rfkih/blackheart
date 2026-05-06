package id.co.blackheart.service.user;

import id.co.blackheart.model.EmailVerificationToken;
import id.co.blackheart.model.User;
import id.co.blackheart.repository.EmailVerificationTokenRepository;
import id.co.blackheart.repository.UserRepository;
import id.co.blackheart.service.email.EmailService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

// Mirrors PasswordResetService. Verification is NOT a login gate today;
// the dashboard shows a "verify your email" banner until confirmed.
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailVerificationService {

    private static final int TOKEN_BYTES = 32;
    private static final long DEFAULT_TTL_HOURS = 24;

    private final UserRepository userRepository;
    private final EmailVerificationTokenRepository tokenRepository;
    private final EmailService emailService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.email-verification.ttl-hours:24}")
    private long ttlHours;

    @Value("${app.email-verification.frontend-url:http://localhost:3000/verify-email}")
    private String frontendVerifyUrl;

    /**
     * Issue a verification token for the given user. Caller is the resend
     * endpoint (already authenticated) or the register flow itself. Logs
     * the verify URL at {@code WARN} for ops retrieval until email is wired.
     */
    @Transactional
    public void issueVerificationToken(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));
        if (Boolean.TRUE.equals(user.getEmailVerified())) {
            log.debug("Skip issuing verification token — already verified | userId={}", userId);
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        tokenRepository.invalidateActiveForUser(userId, now);

        long ttl = ttlHours <= 0 ? DEFAULT_TTL_HOURS : ttlHours;
        EmailVerificationToken token = EmailVerificationToken.builder()
                .token(generateToken())
                .userId(userId)
                .createdAt(now)
                .expiresAt(now.plusHours(ttl))
                .build();
        tokenRepository.save(token);

        String verifyUrl = frontendVerifyUrl + "?token=" + token.getToken();
        long ttlForEmail = ttlHours <= 0 ? DEFAULT_TTL_HOURS : ttlHours;
        try {
            emailService.sendEmailVerification(user.getEmail(), verifyUrl, ttlForEmail);
        } catch (RuntimeException e) {
            // Send failed — log the URL so ops can deliver it manually.
            // EmailService already logged the exception detail.
            log.warn("EMAIL VERIFICATION TOKEN ISSUED (email send failed) | userId={} expiresAt={} url={}",
                    userId, token.getExpiresAt(), verifyUrl);
        }
    }

    /**
     * Consume a verification token and mark the user as verified.
     */
    @Transactional
    public void confirm(String tokenValue) {
        if (!StringUtils.hasText(tokenValue)) {
            throw new EntityNotFoundException("Token not found");
        }
        EmailVerificationToken token = tokenRepository.findById(tokenValue)
                .orElseThrow(() -> new EntityNotFoundException("Token not found"));

        if (token.getUsedAt() != null) {
            throw new EntityNotFoundException("Token already used");
        }
        if (token.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new ExpiredVerificationTokenException("Token has expired");
        }

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new EntityNotFoundException("User not found"));

        user.setEmailVerified(Boolean.TRUE);
        userRepository.save(user);

        token.setUsedAt(LocalDateTime.now());
        tokenRepository.save(token);

        log.info("Email verified | userId={}", user.getUserId());
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static class ExpiredVerificationTokenException extends RuntimeException {
        public ExpiredVerificationTokenException(String message) {
            super(message);
        }
    }
}
