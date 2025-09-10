package com.github.rdsc.dev.ProSync.dto;

import com.github.rdsc.dev.ProSync.enums.UserRole;
import com.github.rdsc.dev.ProSync.enums.UserStatus;
import com.github.rdsc.dev.ProSync.model.User;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.springframework.validation.annotation.Validated;

import java.time.Instant;
import java.util.List;

@Validated
public final class UserDto {

    private UserDto(){} // 不可被new
                        // UserDto 只是個「資料袋的命名空間」，裡面裝 RegisterRequest / UserResponse 兩個小類。
                        // 它不是拿來當物件用的，所以把建構子設成私有，避免誤用。

    // 註冊 API 請求 // 前端 -> 後端（JSON 進來） / 把 JSON 變成 Java 物件 --> 反序列化
    @Data // DTO 沒有 JPA 關聯、沒有延遲載入，單純欄位 → 可用 @Data 或 @Getter/@Setter
    @NoArgsConstructor // 沒有參數的建構子，Jackson 才能自己先 new RegisterRequest()，再把 JSON 值放進物件裡
    public static class RegisterRequest{
        @NotBlank
        @Email
        private String email;
        @NotBlank
        @Size(min = 8, max = 128)
        private String password;
    }

    // 註冊 API 回應 (不含 passwordHash) // 前端 -> 後端（JSON 進來） / 把 Java 物件變成 JSON --> 序列化
    @Getter // @Getter 讓 Jackson 能把資料轉成 JSON
    @AllArgsConstructor
    // 回應給前端時，是「把現成的物件變成 JSON」，不需要先 new 空盒子。
    // Jackson 只要讀得到資料就行，通常靠 getter 就夠了。
    public static class UserResponse{
        private Long id;
        private String email;
        private UserStatus status;
        private Instant createAt;
        private Instant updateAt;

        public static UserResponse of(User u){
            return new UserResponse(
                    u.getId(),
                    u.getEmail(),
                    u.getStatus(),
                    u.getCreateAt(),
                    u.getUpdateAt()
            );
        }
    }

    // 登入 API 請求：前端傳來的資料
    @Data
    @NoArgsConstructor
    public static class LoginRequest{
        @NotBlank
        @Email
        private String email;
        @NotBlank
        @Size(min = 8, max = 128)
        private String password;
    }

    // 登入 API 回應：token，也可以附帶角色讓前端顯示
    @Getter
    @AllArgsConstructor
    public static class LoginResponse{
        private String token; // 用 JWT 生成真正的 token
        private List<UserRole> roles;
    }

    @Data
    @NoArgsConstructor
    public static class VerifyRequest {
        @Email
        @NotBlank
        private String email;

        @Pattern(regexp = "\\d{6}", message = "Invalid code input")
        private String code;
    }

    @Getter
    @AllArgsConstructor
    public static class VerifyResponse {
        private Long id;
        private String email;
        private UserStatus status; // ACTIVE
    }

    @Data
    @NoArgsConstructor
    public static class UpdateStatusRequest {
        @NotBlank(message = "status is required")
        private String status;
    }

    @Getter
    @AllArgsConstructor
    public static class UserStatusResponse {
        private Long id;
        private String email;
        private String status;
        private Instant createAt;
        private String verificationCode;


        public static UserStatusResponse of(User u, String verificationCode){
            return new UserStatusResponse(
                    u.getId(),
                    u.getEmail(),
                    u.getStatus().name(),
                    u.getCreateAt(),
                    verificationCode
            );
        }
    }

    @Data
    @NoArgsConstructor
    public static class RoleChangeRequest {
        @NotBlank(message = "role is required")
        private String role; // 允許：USER / ORGANIZER / EXTERNAL_PROVIDER
    }

    // 一次覆蓋全部角色（給「整批設定」用）
    @Data
    @NoArgsConstructor
    public static class RolesReplaceRequest {
        @NotNull(message = "roles is required")
        @Size(min = 0, max = 8, message = "roles size invalid")
        private List<@NotBlank String> roles; // 例如 ["USER","ORGANIZER"]
    }

    @Getter
    @AllArgsConstructor
    public static class UserRolesResponse {
        private Long userId;
        private List<UserRole> roles;
    }
}
