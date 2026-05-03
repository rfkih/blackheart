package id.co.blackheart.controller;

import id.co.blackheart.dto.request.CreateStrategyDefinitionRequest;
import id.co.blackheart.dto.request.UpdateStrategyDefinitionRequest;
import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.service.strategy.StrategyDefinitionService;
import id.co.blackheart.service.user.JwtService;
import id.co.blackheart.util.ResponseCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Admin-managed strategy catalogue.
 *
 * <p>Any authenticated user may list / fetch — the frontend's strategy
 * pickers need to read this to populate dropdowns. Writes require
 * {@code ROLE_ADMIN} (enforced via {@code @PreAuthorize} — see
 * {@link id.co.blackheart.config.SecurityConfig} for the
 * {@code @EnableMethodSecurity} switch).
 */
@RestController
@RequestMapping("/api/v1/strategy-definitions")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "StrategyDefinitionController", description = "Strategy catalogue — admin writes, any-user reads")
public class StrategyDefinitionController {

    private final StrategyDefinitionService service;
    private final JwtService jwtService;

    @GetMapping
    @Operation(summary = "List strategy definitions — paginated, filterable, sortable",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> list(
            @RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {
        // No pagination params at all → preserve the legacy "every row, no
        // envelope" shape so existing callers (strategy pickers, etc.) keep
        // working. The /research panel always passes page/size.
        if (query == null && sort == null && page == null && size == null) {
            return ResponseEntity.ok(ResponseDto.builder()
                    .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                    .data(service.list())
                    .build());
        }
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(service.listPaged(query, sort, page == null ? 0 : page, size == null ? 50 : size))
                .build());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one strategy definition by id",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(service.getById(id))
                .build());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Register a new strategy definition (admin only)",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> create(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody CreateStrategyDefinitionRequest request) {
        String actor = jwtService.extractEmail(authHeader.substring(7));
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseDto.builder()
                .responseCode(HttpStatus.CREATED.value() + ResponseCode.SUCCESS.getCode())
                .data(service.create(request, actor))
                .build());
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update fields on an existing strategy definition (admin only)",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> update(
            @PathVariable UUID id,
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody UpdateStrategyDefinitionRequest request) {
        String actor = jwtService.extractEmail(authHeader.substring(7));
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(service.update(id, request, actor))
                .build());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Deprecate a strategy definition — soft-delete (admin only)",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> deprecate(
            @PathVariable UUID id,
            @RequestHeader("Authorization") String authHeader) {
        String actor = jwtService.extractEmail(authHeader.substring(7));
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(service.deprecate(id, actor))
                .build());
    }
}
