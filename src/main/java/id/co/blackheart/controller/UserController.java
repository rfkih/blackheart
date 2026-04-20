package id.co.blackheart.controller;

import id.co.blackheart.dto.request.LoginRequest;
import id.co.blackheart.dto.request.RegisterUserRequest;
import id.co.blackheart.dto.request.UpdateProfileRequest;
import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.service.user.JwtService;
import id.co.blackheart.service.user.UserService;
import id.co.blackheart.util.ResponseCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    private final UserService userService;
    private final JwtService jwtService;

    // ── Public ────────────────────────────────────────────────────────────────

    @PostMapping("/register")
    @Operation(summary = "Register a new user account")
    public ResponseEntity<ResponseDto> register(@Valid @RequestBody RegisterUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseDto.builder()
                .responseCode(HttpStatus.CREATED.value() + ResponseCode.SUCCESS.getCode())
                .data(userService.register(request))
                .build());
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate with email + password and receive a JWT")
    public ResponseEntity<ResponseDto> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(userService.login(request))
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
        return jwtService.extractUserId(authHeader.substring(7));
    }
}
