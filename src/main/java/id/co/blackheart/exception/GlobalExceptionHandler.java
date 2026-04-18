package id.co.blackheart.exception;

import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.exception.InvalidCredentialsException;
import id.co.blackheart.exception.UserAccountDisabledException;
import id.co.blackheart.exception.UserAlreadyExistsException;
import id.co.blackheart.exception.UserNotFoundException;
import id.co.blackheart.util.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // ── 400 Bad Request ──────────────────────────────────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ResponseDto> handleValidation(MethodArgumentNotValidException ex) {
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.warn("Validation failed: {}", details);
        return badRequest(ResponseCode.BAD_REQUEST_INVALID_INPUT, details);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ResponseDto> handleMissingParam(MissingServletRequestParameterException ex) {
        log.warn("Missing request parameter: {}", ex.getMessage());
        return badRequest(ResponseCode.BAD_REQUEST_REQUIRED_FIELD_MISSING, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ResponseDto> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String msg = "Parameter '" + ex.getName() + "' has invalid value: " + ex.getValue();
        log.warn("Type mismatch: {}", msg);
        return badRequest(ResponseCode.BAD_REQUEST_INVALID_INPUT, msg);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ResponseDto> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Illegal argument: {}", ex.getMessage());
        return badRequest(ResponseCode.ILLEGAL_ARGUMENT, ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ResponseDto> handleIllegalState(IllegalStateException ex) {
        log.warn("Illegal state: {}", ex.getMessage());
        return badRequest(ResponseCode.ILLEGAL_STATE, ex.getMessage());
    }

    // ── 409 Conflict ─────────────────────────────────────────────────────────

    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<ResponseDto> handleUserAlreadyExists(UserAlreadyExistsException ex) {
        log.warn("User already exists: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ResponseDto.builder()
                .responseCode(HttpStatus.CONFLICT.value() + ResponseCode.USER_ALREADY_EXISTS.getCode())
                .responseDesc(ResponseCode.USER_ALREADY_EXISTS.getDescription())
                .errorMessage(ex.getMessage())
                .build());
    }

    // ── 401 Unauthorized ─────────────────────────────────────────────────────

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ResponseDto> handleInvalidCredentials(InvalidCredentialsException ex) {
        log.warn("Invalid credentials: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ResponseDto.builder()
                .responseCode(HttpStatus.UNAUTHORIZED.value() + ResponseCode.INVALID_CREDENTIALS.getCode())
                .responseDesc(ResponseCode.INVALID_CREDENTIALS.getDescription())
                .errorMessage(ex.getMessage())
                .build());
    }

    @ExceptionHandler(UserAccountDisabledException.class)
    public ResponseEntity<ResponseDto> handleAccountDisabled(UserAccountDisabledException ex) {
        log.warn("Account disabled: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ResponseDto.builder()
                .responseCode(HttpStatus.FORBIDDEN.value() + ResponseCode.ACCOUNT_DISABLED.getCode())
                .responseDesc(ResponseCode.ACCOUNT_DISABLED.getDescription())
                .errorMessage(ex.getMessage())
                .build());
    }

    // ── 404 Not Found ────────────────────────────────────────────────────────

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ResponseDto> handleUserNotFound(UserNotFoundException ex) {
        log.warn("User not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ResponseDto.builder()
                .responseCode(HttpStatus.NOT_FOUND.value() + ResponseCode.NOT_FOUND.getCode())
                .responseDesc(ResponseCode.NOT_FOUND.getDescription())
                .errorMessage(ex.getMessage())
                .build());
    }

    // ── 404 Not Found ────────────────────────────────────────────────────────

    @ExceptionHandler(jakarta.persistence.EntityNotFoundException.class)
    public ResponseEntity<ResponseDto> handleEntityNotFound(jakarta.persistence.EntityNotFoundException ex) {
        log.warn("Entity not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ResponseDto.builder()
                .responseCode(HttpStatus.NOT_FOUND.value() + ResponseCode.NOT_FOUND.getCode())
                .responseDesc(ResponseCode.NOT_FOUND.getDescription())
                .errorMessage(ex.getMessage())
                .build());
    }

    // ── 502 Bad Gateway ──────────────────────────────────────────────────────

    @ExceptionHandler(InvalidResponseException.class)
    public ResponseEntity<ResponseDto> handleInvalidResponse(InvalidResponseException ex) {
        log.error("Invalid response from downstream: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ResponseDto.builder()
                .responseCode(HttpStatus.BAD_GATEWAY.value() + ResponseCode.INVALID_RESPONSE_CODE.getCode())
                .responseDesc(ResponseCode.INVALID_RESPONSE_CODE.getDescription())
                .errorMessage(ex.getMessage())
                .build());
    }

    // ── 503 Service Unavailable ──────────────────────────────────────────────

    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<ResponseDto> handleServiceUnavailable(ServiceUnavailableException ex) {
        log.error("Service unavailable: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ResponseDto.builder()
                .responseCode(HttpStatus.SERVICE_UNAVAILABLE.value() + ResponseCode.NETWORK_ERROR.getCode())
                .responseDesc(ResponseCode.NETWORK_ERROR.getDescription())
                .errorMessage(ex.getMessage())
                .build());
    }

    // ── 500 Internal Server Error ────────────────────────────────────────────

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ResponseDto> handleGeneral(Exception ex) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ResponseDto.builder()
                .responseCode(HttpStatus.INTERNAL_SERVER_ERROR.value() + ResponseCode.INTERNAL_SERVER_ERROR.getCode())
                .responseDesc(ResponseCode.INTERNAL_SERVER_ERROR.getDescription())
                .errorMessage("An unexpected error occurred")
                .build());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ResponseEntity<ResponseDto> badRequest(ResponseCode code, String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ResponseDto.builder()
                .responseCode(HttpStatus.BAD_REQUEST.value() + code.getCode())
                .responseDesc(code.getDescription())
                .errorMessage(message)
                .build());
    }
}
