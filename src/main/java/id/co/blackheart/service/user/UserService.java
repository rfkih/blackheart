package id.co.blackheart.service.user;

import id.co.blackheart.dto.request.LoginRequest;
import id.co.blackheart.dto.request.RegisterUserRequest;
import id.co.blackheart.dto.request.UpdateProfileRequest;
import id.co.blackheart.dto.response.LoginResponse;
import id.co.blackheart.dto.response.UserResponse;
import id.co.blackheart.exception.InvalidCredentialsException;
import id.co.blackheart.exception.UserAccountDisabledException;
import id.co.blackheart.exception.UserAlreadyExistsException;
import id.co.blackheart.exception.UserNotFoundException;
import id.co.blackheart.model.User;
import id.co.blackheart.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Core user management service.
 *
 * <p>Handles registration, authentication, and profile operations.
 * All password handling is delegated to {@link PasswordEncoder} (BCrypt).
 * Token issuance is delegated to {@link JwtService}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    private static final String ROLE_USER    = "USER";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String TOKEN_TYPE   = "Bearer";

    // ── Registration ──────────────────────────────────────────────────────────

    /**
     * Registers a new user AND issues an access token in the same response, so
     * the client can transition directly from the signup form into the
     * authenticated app without a separate login round-trip. The response
     * shape matches {@link #login(LoginRequest)} — callers can reuse one
     * mapper on both paths.
     */
    @Transactional
    public LoginResponse register(RegisterUserRequest request) {
        log.info("Registering new user: email={}", request.getEmail());

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new UserAlreadyExistsException(
                    "An account with email '" + request.getEmail() + "' already exists");
        }

        User user = User.builder()
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName())
                .phoneNumber(request.getPhoneNumber())
                .role(ROLE_USER)
                .status(STATUS_ACTIVE)
                .emailVerified(false)
                .build();

        user.setCreatedBy(request.getEmail());
        user.setUpdatedBy(request.getEmail());

        User saved = userRepository.save(user);
        log.info("User registered: userId={}", saved.getUserId());

        return buildLoginResponse(saved);
    }

    // ── Authentication ────────────────────────────────────────────────────────

    @Transactional
    public LoginResponse login(LoginRequest request) {
        log.info("Login attempt: email={}", request.getEmail());

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password"));


        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Invalid email or password");
        }

        if (!STATUS_ACTIVE.equals(user.getStatus())) {
            throw new UserAccountDisabledException(
                    "Account is " + user.getStatus().toLowerCase().replace('_', ' '));
        }

        // Stamp last login without triggering a full entity dirty-check round-trip
        userRepository.updateLastLogin(user.getUserId(), user.getEmail(), LocalDateTime.now());

        log.info("Login successful: userId={}", user.getUserId());
        return buildLoginResponse(user);
    }

    /**
     * Shared response builder for login + register so both endpoints emit the
     * exact same {@link LoginResponse} shape (accessToken, tokenType,
     * expiresIn, user). Any drift between the two used to manifest as a
     * cryptic frontend "cannot read userId of undefined" — keeping a single
     * builder prevents that.
     */
    private LoginResponse buildLoginResponse(User user) {
        String token = jwtService.generateToken(user);
        return LoginResponse.builder()
                .accessToken(token)
                .tokenType(TOKEN_TYPE)
                .expiresIn(jwtService.getExpirationMs() / 1000)
                .user(toResponse(user))
                .build();
    }

    // ── Profile ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public UserResponse getProfile(UUID userId) {
        User user = userRepository.findByUserId(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));
        return toResponse(user);
    }

    @Transactional
    public UserResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        log.info("Updating profile: userId={}", userId);

        User user = userRepository.findByUserId(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));

        if (request.getFullName() != null && !request.getFullName().isBlank()) {
            user.setFullName(request.getFullName());
        }
        if (request.getPhoneNumber() != null) {
            user.setPhoneNumber(request.getPhoneNumber());
        }
        user.setUpdatedBy(user.getEmail());

        return toResponse(userRepository.save(user));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UserResponse toResponse(User user) {
        return UserResponse.builder()
                .userId(user.getUserId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .phoneNumber(user.getPhoneNumber())
                .role(user.getRole())
                .status(user.getStatus())
                .emailVerified(user.getEmailVerified())
                .lastLoginAt(user.getLastLoginAt())
                .createdTime(user.getCreatedTime())
                .updatedTime(user.getUpdatedTime())
                .build();
    }
}
