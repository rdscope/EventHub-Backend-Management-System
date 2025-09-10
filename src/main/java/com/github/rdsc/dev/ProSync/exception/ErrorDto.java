package com.github.rdsc.dev.ProSync.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.Builder;
import lombok.Data;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
public final class ErrorDto {
    private String timestamp;           // ISO-8601
    private int status;                 // 例如 400
    private String error;               // 例如 "Bad Request"
    private String message;             // 具體錯誤訊息
    private String path;                // 例如 /api/orders
    private Map<String, String> fieldErrors; // 欄位錯誤（可為 null）

    // 建立標準錯誤物件（含可選的 fieldErrors）
    public static ErrorDto toResponse(HttpStatus status,
                                      String message,
                                      HttpServletRequest req,
                                      Map<String, String> fieldErrors) {

        return ErrorDto.builder()
                .timestamp(Instant.now().toString())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .path(req.getRequestURI())
                .fieldErrors(fieldErrors)
                .build();
    }

    // 不帶 fieldErrors 的簡便版
    public static ErrorDto toResponse(HttpStatus status,
                                      String message,
                                      HttpServletRequest req) {
        return toResponse(status, message, req, null);
    }
}
