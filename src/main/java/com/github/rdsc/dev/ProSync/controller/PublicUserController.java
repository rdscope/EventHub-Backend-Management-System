package com.github.rdsc.dev.ProSync.controller;

import com.github.rdsc.dev.ProSync.enums.UserRole;
import com.github.rdsc.dev.ProSync.enums.UserStatus;
import com.github.rdsc.dev.ProSync.repository.UserRepository;
import com.github.rdsc.dev.ProSync.security.JwtUtil;
import com.github.rdsc.dev.ProSync.dto.UserDto;
import com.github.rdsc.dev.ProSync.model.User;
import com.github.rdsc.dev.ProSync.security.SecGenCode;
import com.github.rdsc.dev.ProSync.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.server.ResponseStatusException;


import java.time.Duration;
import java.util.List;
import java.util.Map;





@RequiredArgsConstructor
@RestController
@RequestMapping("/api/users")
@Slf4j
public class PublicUserController {

    private final UserService userService;
    private final SecGenCode secGenCode;
    private final JwtUtil jwtUtil;

    // @Autowired
//    public UserController(UserService userService, JwtUtil jwtUtil, UserRepository userRepo){
//        this.userService = userService;
//        this.userRepo = userRepo;
//        this.jwtUtil = jwtUtil;
//    }

    @PostMapping("/register") // POST /api/users/register
    public ResponseEntity<?> register(@RequestBody @Valid UserDto.RegisterRequest req){ // @Valid：啟用驗證，阻擋「空密碼/不合法 Email」

        if (req == null) {
            throw new IllegalArgumentException("request body is required");
        }
        if (isBlank(req.getEmail()) || isBlank(req.getPassword())) {
            throw new IllegalArgumentException("email and password are required");
        }

        try {

            // 1/ 進行註冊
            User u = userService.register(req.getEmail(), req.getPassword());

//            // 2/ 取角色名稱（避免 Lazy/序列化循環）
//            List<String> roleNames = (u.getRoles() == null)
//                    ? List.of() // 給一個空清單，避免 NPE
//                    : u.getRoles().stream().map(r -> r.getName()).distinct().toList(); // 把每個 Role 物件，只取出名字，去掉重複的名字，把結果收集回成清單

            if (u.getStatus() != UserStatus.PENDING_VERIFICATION) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Now is in " + u.getStatus() + ", cannot generate verification code");
            }

            String code = secGenCode.issueCodeAfterDelay(u.getEmail(), Duration.ofSeconds(SecGenCode.DEFAULT_CODE_DL_SECONDS), SecGenCode.DEFAULT_CODE_TTL_SECONDS);

            // 3/ 成功回傳DTO
            return ResponseEntity.ok(new UserDto.UserStatusResponse(u.getId(), u.getEmail(), u.getStatus().name(),u.getCreateAt(),code));
//            return ResponseEntity.status(201)
//                    .body(new UserDto.Response(u.getId(), u.getEmail(), roleNames));

        } catch (IllegalArgumentException ex) {
            // 已存在等註冊錯誤
            return ResponseEntity.badRequest().body(new UserDto.UserStatusResponse(null, null, "BAD_REQUEST", null, ex.getMessage()));
        } catch (IllegalStateException ex) {
            // 例如狀態不是 PENDING_VERIFICATION
            return ResponseEntity.status(409).body(new UserDto.UserStatusResponse(null, null, "CONFLICT", null, ex.getMessage()));
        }
    }

    @PostMapping("/verify")
    public ResponseEntity<UserDto.VerifyResponse> verify(@RequestBody @Valid UserDto.VerifyRequest req
    ) {
        // 1/ 先找出使用者（找不到 → 400）
        var user = userService.findByEmail(req.getEmail())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        // 2/ 呼叫驗證服務：存在 + 未過期 + 碼相同 才會回 true，並且內部會把狀態設為 ACTIVE
        boolean ok = secGenCode.verifyAndActivate(user.getEmail(), req.getCode());
        if (!ok) {
            throw new IllegalArgumentException("The verification code is incorrect or has expired"); // 交給 GlobalExceptionHandler 回 400
        }

        // 3/ 回應（此時狀態已是 ACTIVE）
        return ResponseEntity.ok(
                new UserDto.VerifyResponse(user.getId(), user.getEmail(), user.getStatus())
        );
    }

    @PostMapping("/login") // POST /api/users/login
    public ResponseEntity<?> login(@RequestBody @Valid UserDto.LoginRequest req) {
//        try {

            // 1/ 交給 service 驗證帳密
            User u = userService.authenticate(req.getEmail(), req.getPassword());

            // 2/ 把角色物件轉成名稱字串
            List<UserRole> roleNames = (u.getRoles() == null)
                    ? List.of()
                    : u.getRoles().stream().map(r -> r.getUserRole()).distinct().toList();

            // 檢查狀態
            switch (u.getStatus()) {
                case ACTIVE -> {

                    // 3/ 回 Fake token / JWT
                    // String fakeToken = "TEMP-" + u.getId() + "-" + System.currentTimeMillis();
                    String Token = jwtUtil.generateToken(u.getId(), u.getEmail(), roleNames);

                    return ResponseEntity.ok(new UserDto.LoginResponse(Token, roleNames));
                }
                case PENDING_VERIFICATION -> throw new IllegalStateException("Your account has not been verified yet. Please complete the verification before logging in."); // 409
                case SUSPENDED -> throw new IllegalStateException("Your account has been suspended. Please contact the administrator."); // 409
                case DISABLED -> throw new IllegalStateException("Account disabled."); // 409
                default -> throw new IllegalStateException("The account status is abnormal."); // 409
            }


//        } catch (IllegalArgumentException ex) {
//
//            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials"));
//
//        }

    }

    private boolean isBlank(String s){ // 為了「防止 NPE（空指標錯誤）」
                                       // 前端送來的 JSON 沒有 email 欄位（或是 null），req.getEmail() 就是 null，
                                       // 這時呼叫 .isBlank() 會直接噴 NullPointerException → 伺服器 500
        return s == null || s.isBlank(); // isBlank(req.getEmail())：null 或空白都當作「不合法」
    }

    @GetMapping("/is-me") // 受保護：要帶 Authorization: Bearer <JWT>
    public ResponseEntity<Map<String, Object>> me() { // 回應包裝型別且其泛型本體是 Map<String, Object> 表示 JSON 結構鍵為 String 值為 Object
        Authentication auth = SecurityContextHolder.getContext().getAuthentication(); // 取得目前安全內容中，取得當前認證物件

        if (auth == null || auth.getName() == null || !auth.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No authenticated user");
        }
//        if(auth == null || !auth.isAuthenticated()){
//            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized")); // 回應主體 Map
//        }

        String email = (String) auth.getPrincipal(); // 強制轉型 (String) 取得 auth.getPrincipal() 的主體
        User user = userService.findByEmail(email).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + email));

        List<String> roles = auth.getAuthorities().stream() // 取得權限集合
                .map(GrantedAuthority:: getAuthority) // 方法：將每個權限物件映射成其權限名稱字串：
                                                      // 型別轉換：Stream<GrantedAuthority> → Stream<String>
                // .map(ga -> ga.getAuthority())
                .map(s -> s.startsWith("ROLE_") ? s.substring(5) : s) // 去掉 ROLE_ 前綴
                .distinct()
                .toList();

        return ResponseEntity.ok(Map.of(
                "userId", user.getId(),
                "email", email,
                "status", user.getStatus(),
                "roles", roles
        ));

        // 1. 過濾器先看門（JwtAuthFilter）：從 Authorization: Bearer <token> 取出 token，驗證成功就把「已登入身份（email + roles）」塞進 SecurityContext；失敗就清空身份。
        // 2. 安全規則在中間（SecurityConfig）：只有 /register、/login 放行，其它（包含 /is-me）都要求「已登入」。
        // 3. Controller 最後判斷（me() 那段）：
        //      如果 SecurityContext 裡沒有已登入 → 回 401。
        //      如果有已登入 → 取出 email 和 roles，回 200 + JSON。
        // (1) 請求 /api/users/is-me 進來
        // (2) SecurityConfig 建好的 SecurityFilterChain 先跑
        //     └─ 規則：/register、/login 放行；其他要驗證
        // (3) 進入 JwtAuthFilter（被 SecurityConfig 掛上去了）
        //     ├─ 讀 Authorization: Bearer <JWT>
        //     ├─ 用 JwtUtil.verify(token) 驗證
        //     └─ 驗證成功 → 把已登入身分放進 SecurityContext
        // (4) 驗證都 OK 才會進到 Controller 方法
        //     └─ 在 Controller 用 SecurityContextHolder 拿到 email / roles
    }


}
