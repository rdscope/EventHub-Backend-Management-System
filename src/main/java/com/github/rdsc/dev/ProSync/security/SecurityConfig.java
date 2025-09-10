package com.github.rdsc.dev.ProSync.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.time.Instant;

@Configuration // 設定類別
@EnableWebSecurity // 開啟 Spring Security 的 Web 模組，建立並註冊整條 Security Filter Chain
@EnableMethodSecurity(prePostEnabled = true) // 讓 @PreAuthorize/@PostAuthorize 生效
public class SecurityConfig { // 宣告一個具名類別，承載安全設定之組態邏輯

    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter){
        this.jwtAuthFilter = jwtAuthFilter; // 建構子接受同名參數 jwtAuthFilter 以由 Spring DI 注入，
                                            // 並將參數指派給成員 this.jwtAuthFilter 完成依賴設定
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // 定義一條安全規則鏈（Filter Chain），接受 HttpSecurity (DSL 物件)：所有請求進來，要走哪些關卡
        // DSL 物件是承載這個小語言的一個 Java 物件，上面有一堆可鏈式呼叫的方法，可以用「像句子」的方式把設定講完

        // 1/ REST API 常用：關 CSRF、改成無狀態
        // 子 DSL（有 cfg -> 的）：要描述規則 - 用來「設定某一塊主題」的屬性與規則
        http.csrf(csrf -> csrf.disable());
        // 因為是純後端 API + JWT，不是表單登入，不需要 CSRF
        // 在 http 安全 DSL 上呼叫 csrf 並以 Lambda 取得 CsrfConfigurer，

        http.sessionManagement(sm -> sm
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        // 不用 伺服器 Session 記登入 (使 Spring Security 不建立 HTTP Session)，
        // 因為用 JWT，每次請求都帶 Token 來證明身分
        // 好處：可水平擴充、不用黏住同一台機器

        // 例外處理：沒登入=401；沒權限=403（配合 GlobalExceptionHandler）
        http.exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) -> res.sendError(HttpServletResponse.SC_UNAUTHORIZED))
                .accessDeniedHandler((req, res, e) -> res.sendError(HttpServletResponse.SC_FORBIDDEN)));

        // 2/ 授權規則：放行註冊/登入，其餘要驗證  URL 規則 和 @PreAuthorize 雙保險
        http.authorizeHttpRequests(auth -> auth
                // Swagger / OpenAPI 白名單
                .requestMatchers("/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html").permitAll()
                // 白名單：註冊/登入/健康檢查/公開查詢（依路徑先放常見幾條）
                .requestMatchers("/api/users/register",
                                    "/api/users/login",
                                    "/api/users/verify",
                                    "/api/public/**",
                                    "/actuator/health",
                                    "/error"
                                ).permitAll()

                // 管理端：必須 ADMIN
                .requestMatchers("/api/admin/**", "/api/tickets/admin/**", "/api/events/admin/**").hasRole("ADMIN")

                // 使用者功能：下單、付款 需 USER（ADMIN 也可另外在方法級放行）
                .requestMatchers("/api/orders/**", "/api/payments/**").hasRole("USER")
                // 主辦後台：ORGANIZER 或 ADMIN
                .requestMatchers("/api/tickets/organizer/**", "/api/events/organizer/**").hasAnyRole("ORGANIZER", "ADMIN")
                // 外部供應商：必須 EXTERNAL_PROVIDER
                .requestMatchers("/api/external/**").hasAnyRole("EXTERNAL_PROVIDER")
                // 其他全部要登入
                .anyRequest().authenticated() // 其餘透過 anyRequest().authenticated() 強制需經驗證
        );
        // /api/users/register、/api/users/login 不需要 JWT！（因為 .permitAll()）
        // /api/admin/** 需要「JWT + 角色 ADMIN」（因為 .hasRole("ADMIN")）
        // 其他所有路由都需要「JWT」（因為 .anyRequest().authenticated()）

        // 3/ 統一錯誤回應：指定 未登入=401，權限不足=403（回簡單 JSON）
        http.exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, exc) -> {
                    res.setStatus(HttpServletResponse.SC_UNAUTHORIZED); // 401
                    res.setContentType("application/json;charset=UTF-8");
                    String json = String.format(
                            "{\"timestamp\":\"%s\",\"status\":401,\"error\":\"Unauthorized\",\"message\":\"Unauthorized\",\"path\":\"%s\"}",
                            Instant.now().toString(), req.getRequestURI()
                    );
                    res.getWriter().write(json);
                }) // 處理未認證請求回 401，
                .accessDeniedHandler((req, res, exc) -> {
                    res.setStatus(HttpServletResponse.SC_FORBIDDEN); // 403
                    res.setContentType("application/json;charset=UTF-8");
                    String json = String.format(
                            "{\"timestamp\":\"%s\",\"status\":403,\"error\":\"Forbidden\",\"message\":\"Forbidden\",\"path\":\"%s\"}",
                            Instant.now().toString(), req.getRequestURI()
                    );
                    res.getWriter().write(json);
                }) // 處理已認證但權限不足回 403
        );

        // 4/ 把 JWT 過濾器 掛在 UsernamePasswordAuthenticationFilter 之前
        // 直接動作方法（沒有 ->）：要插/移 filter - 用來「對整個鏈做結構性操作」或立即性設定
        http.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        // 把 jwtAuthFilter 以 addFilterBefore 掛載在 UsernamePasswordAuthenticationFilter.class 之前，
        // 使 Bearer Token 驗證先於表單登入過濾器執行。

        return http.build();
        // 呼叫 build() 將前述 DSL 組態物件化為 SecurityFilterChain 並由方法回傳給容器。
    }

    // 提供 AuthenticationManager（登入用得到）
    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }

    // PasswordEncoder（建議用 Bean，不要在 Service 內 new）
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

//    @Bean
//    AuthenticationEntryPoint unauthorizedEntryPoint() { // 認證失敗例外
//        return(request, response, authException) -> {
//            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); // 401
//            response.setContentType("application/json;charset=UTF-8"); // 宣告 JSON 與編碼
//            response.getWriter().write("{\"error\":\"Unauthorized\"}"); // 寫出錯誤主體
//        };
//    }
//
//    @Bean
//    AccessDeniedHandler forbiddenHandler() { // 授權拒絕
//        return(request, response, accessDeniedException) -> {
//            response.setStatus(HttpServletResponse.SC_FORBIDDEN); // 403
//            response.setContentType("application/json;charset=UTF-8");
//            response.getWriter().write("{\"error\":\"Forbidden\"}");
//        };
//    }
}
