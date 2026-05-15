package id.co.blackheart.controller;

import id.co.blackheart.dto.request.LoginRequest;
import id.co.blackheart.dto.request.RegisterUserRequest;
import id.co.blackheart.dto.request.UpdateProfileRequest;
import id.co.blackheart.dto.response.LoginResponse;
import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.service.user.JwtCookieService;
import id.co.blackheart.service.user.JwtService;
import id.co.blackheart.service.user.UserService;
import id.co.blackheart.util.AuthHeaderUtil;
import id.co.blackheart.util.ResponseCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * User registration, authentication, and profile management.
 *
 * <p>Public endpoints:
 * <ul>
 *   <li>POST /api/v1/users/register — create a new account</li>
 *   <li>POST /api/v1/users/login    — authenticate and receive JWT</li>
 * </ul>
 *
 * <p>Protected endpoints (require {@code Authorization: Bearer <token>}):
 * <ul>
 *   <li>GET   /api/v1/users/me — fetch own profile</li>
 *   <li>PATCH /api/v1/users/me — update own profile</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "UserController", description = "User registration, authentication, and profile management")
public class UserController {

    private static final String MESSAGE_KEY = "message";

    private final UserService userService;
    private final JwtService jwtService;
    private final JwtCookieService jwtCookieService;
    private final id.co.blackheart.service.user.PasswordResetService passwordResetService;
    private final id.co.blackheart.service.user.EmailVerificationService emailVerificationService;

    // ── Public ────────────────────────────────────────────────────────────────

    @PostMapping("/register")
    @Operation(summary = "Register a new user account")
    public ResponseEntity<ResponseDto> register(
            @Valid @RequestBody RegisterUserRequest request,
            HttpServletResponse httpResponse) {
        LoginResponse resp = userService.register(request);
        jwtCookieService.issue(httpResponse, resp.getAccessToken());
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseDto.builder()
                .responseCode(HttpStatus.CREATED.value() + ResponseCode.SUCCESS.getCode())
                .data(resp)
                .build());
    }

    @PostMapping("/password-reset/request")
    @Operation(
            summary = "Request a password-reset token",
            description = "Always returns 200 — the response is the same whether or not the email matches a real "
                    + "account, to prevent enumeration. When the email matches, a token is issued and the reset "
                    + "URL is delivered by email (with a WARN-level URL log on send failure for ops recovery)."
    )
    public ResponseEntity<ResponseDto> requestPasswordReset(
            @Valid @RequestBody id.co.blackheart.dto.request.PasswordResetRequestRequest request) {
        passwordResetService.requestReset(request.getEmail());
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(java.util.Map.of(
                        MESSAGE_KEY, "If an account exists with that email, reset instructions have been issued."
                ))
                .build());
    }

    @PostMapping("/password-reset/confirm")
    @Operation(
            summary = "Confirm a password-reset token and set a new password",
            description = "Returns 404 if the token is unknown or already used; 410 if expired; 400 on validation."
    )
    public ResponseEntity<ResponseDto> confirmPasswordReset(
            @Valid @RequestBody id.co.blackheart.dto.request.PasswordResetConfirmRequest request) {
        try {
            passwordResetService.confirmReset(request.getToken(), request.getNewPassword());
            return ResponseEntity.ok(ResponseDto.builder()
                    .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                    .data(java.util.Map.of(MESSAGE_KEY, "Password updated. You can now log in."))
                    .build());
        } catch (id.co.blackheart.service.user.PasswordResetService.ExpiredResetTokenException e) {
            return ResponseEntity.status(HttpStatus.GONE).body(ResponseDto.builder()
                    .responseCode(HttpStatus.GONE.value() + ResponseCode.CUSTOM_ERROR.getCode())
                    .data(java.util.Map.of(MESSAGE_KEY, e.getMessage()))
                    .build());
        }
    }

    @PostMapping("/email/verify")
    @Operation(
            summary = "Confirm an email-verification token",
            description = "Public endpoint — the token itself is the auth. 404 on unknown/used; 410 on expired."
    )
    public ResponseEntity<ResponseDto> verifyEmail(
            @Valid @RequestBody id.co.blackheart.dto.request.EmailVerifyRequest request) {
        try {
            emailVerificationService.confirm(request.getToken());
            return ResponseEntity.ok(ResponseDto.builder()
                    .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                    .data(java.util.Map.of(MESSAGE_KEY, "Email verified."))
                    .build());
        } catch (id.co.blackheart.service.user.EmailVerificationService.ExpiredVerificationTokenException e) {
            return ResponseEntity.status(HttpStatus.GONE).body(ResponseDto.builder()
                    .responseCode(HttpStatus.GONE.value() + ResponseCode.CUSTOM_ERROR.getCode())
                    .data(java.util.Map.of(MESSAGE_KEY, e.getMessage()))
                    .build());
        }
    }

    @PostMapping("/email/resend-verification")
    @Operation(
            summary = "Re-issue an email-verification token for the authenticated user",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<ResponseDto> resendVerification(
            @RequestHeader("Authorization") String authHeader) {
        java.util.UUID userId = jwtService.extractUserId(AuthHeaderUtil.extractToken(authHeader));
        emailVerificationService.issueVerificationToken(userId);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(java.util.Map.of(
                        MESSAGE_KEY, "Verification instructions issued."
                ))
                .build());
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate with email + password; issues HttpOnly JWT cookie and echoes token in body")
    public ResponseEntity<ResponseDto> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletResponse httpResponse) {
        LoginResponse resp = userService.login(request);
        jwtCookieService.issue(httpResponse, resp.getAccessToken());
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(resp)
                .build());
    }

    @PostMapping("/logout")
    @Operation(summary = "Clear the authentication cookie")
    public ResponseEntity<ResponseDto> logout(HttpServletResponse httpResponse) {
        jwtCookieService.clear(httpResponse);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(Map.of("logout", true))
                .build());
    }

    /**
     * Short-lived ticket for opening a STOMP WebSocket. Returns a JWT that is a
     * 60-second copy of the authenticated principal — the client includes it
     * in the STOMP CONNECT frame's {@code Authorization} header.
     *
     * <p>This endpoint exists because HttpOnly cookies cannot be read by JS to
     * attach to the STOMP CONNECT frame. Instead, JS fetches a ticket
     * (authenticated via the HttpOnly cookie) and uses the ticket exclusively
     * for WS auth. The ticket's short TTL keeps exposure minimal even if the
     * ticket leaks through, e.g., a dev-tools copy-paste.
     */
    @GetMapping("/ws-ticket")
    @Operation(summary = "Issue a short-lived JWT for opening the WebSocket",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> wsTicket(
            @RequestHeader("Authorization") String authHeader) {
        UUID userId = extractUserId(authHeader);
        String email = jwtService.extractEmail(AuthHeaderUtil.extractToken(authHeader));
        String role = jwtService.extractRole(AuthHeaderUtil.extractToken(authHeader));
        String ticket = jwtService.generateShortLivedTicket(email, userId, role);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(Map.of("ticket", ticket, "expiresInSeconds", 60))
                .build());
    }

    // ── Protected (JWT required) ──────────────────────────────────────────────

    @GetMapping("/me")
    @Operation(summary = "Get the currently authenticated user's profile",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> getMyProfile(
            @RequestHeader("Authorization") String authHeader) {
        UUID userId = extractUserId(authHeader);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(userService.getProfile(userId))
                .build());
    }

    @PatchMapping("/me")
    @Operation(summary = "Update the currently authenticated user's profile",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> updateMyProfile(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody UpdateProfileRequest request) {
        UUID userId = extractUserId(authHeader);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(userService.updateProfile(userId, request))
                .build());
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private UUID extractUserId(String authHeader) {
        return jwtService.extractUserId(AuthHeaderUtil.extractToken(authHeader));
    }
}
