package com.github.rdsc.dev.ProSync.exception;

import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.github.rdsc.dev.ProSync.exception.ErrorDto.toResponse;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 400 handleValidation、handleIllegalArgument、handleConstraintViolation、handleNotReadable：驗證失敗、參數錯
    //     (MethodArgumentNotValidException、IllegalArgumentException、ConstraintViolationException、HttpMessageNotReadableException)
    // 401 handleAuthErrors：未登入/登入錯
    //     (BadCredentialsException、UsernameNotFoundException)
    // 403 handleAccessDenied：沒權限
    //     (AccessDeniedException)
    // 409 handleConflictKnown、handleOptimisticLock、handleIllegalState：狀態衝突（庫存不足、已過期）、樂觀鎖衝突
    //     (DataIntegrityViolationException、ObjectOptimisticLockingFailureException、OptimisticLockException、IllegalStateException)
    // 429 handleRse：分散式鎖失敗
    //     (ResponseStatusException)
    // 500：handelOther其他未預期
    //     (Exception)

    // 400：驗證失敗 / 參數錯
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorDto> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {

//        String fieldErrors = ex.getBindingResult().getFieldErrors().stream()
//                             .map(fe -> fe.getField() + " " + (fe.getDefaultMessage() == null ? "invalid" : fe.getDefaultMessage()))
//                             .collect(Collectors.joining("; "));
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for(FieldError fe : ex.getBindingResult().getFieldErrors()) { // BindingResult，裡面裝著「資料綁定＋驗證」的全部結果（成功/失敗、哪個欄位錯、錯在哪）
                                                                      // FieldErrors()「欄位級」錯誤：從 BindingResult 裡抓出「每個欄位」的錯誤清單（List<FieldError>）。一個欄位可能有多個錯（例如同時「必填」＋「格式不對」）
                                                                      // 補充：getAllErrors() 會包含「物件級」錯誤；getGlobalErrors() 只拿「非欄位」的錯誤
            fieldErrors.put(fe.getField(), fe.getDefaultMessage());
        }
        log.warn("400 MethodArgumentNotValid: {} {}", req.getMethod(), req.getRequestURI());
//        Map<String, Object> body = new LinkedHashMap<>();
//        body.put("error", "VALIDATION_FAILED");
//        body.put("field", fieldErrors);
//        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(toResponse(HttpStatus.BAD_REQUEST, "Request validation failed", req, fieldErrors));
    }

    // 400：@Validated / QueryString 參數驗證失敗
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorDto> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest req) {
        Map<String, String> fieldErrors = new HashMap<>();
        ex.getConstraintViolations().forEach(v -> fieldErrors.put(v.getPropertyPath().toString(), v.getMessage()));
        log.warn("400 ConstraintViolation: {} {}", req.getMethod(), req.getRequestURI());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(toResponse(HttpStatus.BAD_REQUEST, "Request validation failed", req, fieldErrors));
    }

    // 400：JSON 解析錯（缺欄位 / 型別錯）
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorDto> handleNotReadable(HttpMessageNotReadableException ex, HttpServletRequest req) {
        log.warn("400 NotReadable: {} {}", req.getMethod(), req.getRequestURI());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(toResponse(HttpStatus.BAD_REQUEST, "Malformed JSON request", req, null));
    }

    // 400：商業邏輯的「參數不合法」
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorDto> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest req) {
        log.warn("400 IllegalArgument: {} {} - {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(toResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), req, null));
    }

    // 401：登入錯（密碼錯、找不到帳號）
    @ExceptionHandler({BadCredentialsException.class, UsernameNotFoundException.class})
    public ResponseEntity<ErrorDto> handleAuthErrors(RuntimeException ex, HttpServletRequest req) {
        log.warn("401 Unauthorized: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(toResponse(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED: Invalid credentials", req, null));
    }

    // 403：已登入但沒權限
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorDto> handleAccessDenied(AccessDeniedException ex, HttpServletRequest req) {
        log.warn("403 Forbidden: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(toResponse(HttpStatus.FORBIDDEN, "FORBIDDEN: Access denied", req, null));
    }

    // 409：資料狀態衝突（唯一鍵、外鍵等約束）
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorDto> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest req) {
        log.warn("409 DataIntegrity: {} {}", req.getMethod(), req.getRequestURI());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(toResponse(HttpStatus.CONFLICT, "Data integrity violation", req, null));
    }

    // 409：樂觀鎖衝突（版本不一致）— 若服務層未改丟 429，預設 409
    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, OptimisticLockException.class})
    public ResponseEntity<ErrorDto> handleOptimistic(Object ex, HttpServletRequest req) {
        log.warn("409 OptimisticLock: {} {}", req.getMethod(), req.getRequestURI());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(toResponse(HttpStatus.CONFLICT, "Concurrent update conflict", req, null));
    }

    // 409：不符合狀態的操作（例如已過期、非待付款）
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorDto> handleIllegalState(IllegalStateException ex, HttpServletRequest req) {
        log.warn("409 IllegalState: {} {} - {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(toResponse(HttpStatus.CONFLICT, ex.getMessage(), req, null));
    }
//    public ResponseEntity<ErrorDto> handleIllegalState(IllegalStateException ex, HttpServletRequest req) {
//        String msg = ex.getMessage() == null ? "" : ex.getMessage();
//
//        if (msg.toLowerCase().startsWith("forbidden")) {
//            log.warn("403 Forbidden: {}", msg);
//            return toResponse(HttpStatus.FORBIDDEN, "FORBIDDEN", msg, req);
//        }
//
//        log.warn("409 Conflict: {}", msg);
//        return toResponse(HttpStatus.CONFLICT, "STATE_CONFLICT", msg, req);
//    }

//    private Map<String, Object> body(String error, String message, Map<String, String> fields) {
//        Map<String, Object> b = new LinkedHashMap<>();
//        b.put("error", error);
//        if (message != null) b.put("message", message);
//        if (fields != null) b.put("fields", fields);
//        b.put("timestamp", Instant.now().toString());
//        return b;
//    }

    // ResponseStatusException：沿用服務層自訂的狀態碼（含 429 等）
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorDto> handleRse(ResponseStatusException ex, HttpServletRequest req) {
        HttpStatus status = (ex.getStatusCode() instanceof HttpStatus hs) ? hs
                : HttpStatus.valueOf(ex.getStatusCode().value());
        String message = (ex.getReason() == null || ex.getReason().isBlank())
                ? status.getReasonPhrase() : ex.getReason();

        if (status.is5xxServerError()) {
            log.error("{} {}: {}", status.value(), status.getReasonPhrase(), message, ex);
        } else {
            log.warn("{} {}: {}", status.value(), status.getReasonPhrase(), message);
        }
        return ResponseEntity.status(status).body(toResponse(status, message, req, null));
    }

    // 未知例外回 -> 500，避免把堆疊丟給前端
    @ExceptionHandler(Exception.class)
//    public ResponseEntity<Map<String, Object>> handleOther(Exception ex,
//                                                           HttpServletRequest req) {
    public ResponseEntity<ErrorDto> handleOther(Exception ex, HttpServletRequest req) {
                                                        // 要知道錯在哪個 URL：把 path 放進錯誤 JSON
                                                        // 要知道 HTTP API 方法 / 查詢字串：能寫進 log 或錯誤回應
                                                        // 不必手動建立，Spring @ExceptionHandler 會自動注入
//        Map<String, Object> body = Map.of(
//                "error", "InternalServerError",
//                "path", req.getRequestURI(),  // <- 需要 req 才拿得到
//                "timestamp", Instant.now().toString()
//        );
        log.error("500 Unexpected error on {} {}: {}", req.getMethod(), req.getRequestURI(), ex.toString(), ex);
        // Map<String, Object> b = body("InternalServerError", ex.getMessage(), null);
        // b.put("path", req.getRequestURI()); // 從請求拿到路徑（URI 的 path 部分），
                                               // 例如：/api/orders/123/pay（不含網域、不含查詢字串）。

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(toResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", req, null));
//        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body("INTERNAL_ERROR", "Please contact for support", null)); // 500
    }

    // 分類用
    @SuppressWarnings("unused")
    private void logAtLevel(Throwable ex) {
        if (ex instanceof ResponseStatusException rse) {
            int code = rse.getStatusCode().value();
            if (code >= 500) log.error("RSE {}", code, ex);
            else log.warn("RSE {}", code, ex);
        } else if (ex instanceof MethodArgumentNotValidException
                || ex instanceof IllegalArgumentException
                || ex instanceof ConstraintViolationException
                || ex instanceof HttpMessageNotReadableException) {
            log.warn("400 Bad Request", ex);
        } else if (ex instanceof BadCredentialsException || ex instanceof UsernameNotFoundException) {
            log.warn("401 Unauthorized", ex);
        } else if (ex instanceof AccessDeniedException) {
            log.warn("403 Forbidden", ex);
        } else if (ex instanceof ObjectOptimisticLockingFailureException || ex instanceof OptimisticLockException) {
            log.warn("409 Optimistic Lock", ex);
        } else if (ex instanceof DataIntegrityViolationException || ex instanceof IllegalStateException) {
            log.warn("409 Conflict", ex);
        } else {
            log.error("500 Internal Server Error", ex);
        }
    }

}


