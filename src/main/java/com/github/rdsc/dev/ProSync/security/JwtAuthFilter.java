package com.github.rdsc.dev.ProSync.security;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
// 引入 解碼後的 JWT 物件 型別，驗完 token 會拿到它。
import jakarta.servlet.FilterChain;
// 引入 Filter 相關介面與 HTTP 請求/回應 型別，Filter 需要用。
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
// 代表「已登入身分」的物件
import org.springframework.security.core.authority.SimpleGrantedAuthority;
// 代表「一個權限/角色」
import org.springframework.security.core.context.SecurityContextHolder;
// 放/取「目前請求的登入身分」
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
// 幫身分加上一些請求細節（IP、User-Agent 等）

import java.io.IOException;
import java.util.List;

@Component
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter { // 這是「過濾器」基底類別，確保 「每個請求只跑一次」
                                                          // 每次 HTTP 請求來，都會先經過它一次
    // 讓之後受保護的 API 能從 Authorization: Bearer <token> 自動驗證登入身分。

    private final JwtUtil jwtUtil;
    // 利用 JwtUtil 驗證 token 與拿出 claims

    public JwtAuthFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected  void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException{
        // 每次有 HTTP 請求進來，都會走到這裡一次 (chain：把請求往下一個 Filter/Controller 繼續傳下去)
        // 如果標頭有帶 Bearer <token>，就驗證它並把「登入身分」放進 SecurityContext；
        // 如果沒有或不合法，就不要放任何身分，讓後面的規則去擋。

        // 如果已經有身分，就不重複解析（例如前面別的過濾器已經設好）
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization"); // 取出 Authorization

        if (header != null && header.toLowerCase().startsWith("bearer ")) { // 如果以 Bearer 開頭（注意後面有空白），才視為 JWT
            String token = header.substring(7).trim(); // Authorization: Bearer <JWT>

            try{
                // 1/ 驗證與解析 JWT
                DecodedJWT jwt = jwtUtil.verify(token); // 丟給 JwtUtil 驗證 做簽章與有效期檢查
                                                        // 成功會拿到 DecodedJWT（裡面有 sub、email、roles 等 claims）；失敗會丟例外

                // 2/ 取出使用者資訊與角色
                String email = jwt.getClaim("email").asString(); // 從 claims 把 email 拿出來（簽 token 時有放）

                List<String> roles = jwtUtil.getRoles(jwt); // 用 jwtUtil.getRoles(jwt) 取出字串清單：例如 ["USER"]
                // List<String> roles = jwt.getClaim("roles").asList(String.class);

                // 3/ 轉成 Spring Security 權限物件
                List<SimpleGrantedAuthority> auths = (roles == null ? List.<String>of() : roles)
                        .stream().distinct().map(r -> r.startsWith("ROLE_") ? r : "ROLE_" + r)
                        .map(SimpleGrantedAuthority::new) // Security 內部的權限型別
                        .toList();
                // 把 ["USER"] 轉成 ["ROLE_USER"]，因為 Spring Security 的預設「角色」要有 ROLE_ 前綴

                // 4/ 建立「已登入身分」並放進 SecurityContext
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(email, null, auths);
                                                                // 第二個參數是「憑證」（密碼），這裡不需要所以放
                // 這行在做一張 Authentication 物件（Spring 的“內場票”）：
                // principal（第一個參數）：放 email（也可以放 userId 或自訂 UserDetails）。
                // credentials（第二個參數）：放 null，因為密碼驗證早就靠 JWT 完成了，這裡不再保存密碼。
                // authorities（第三個參數）：剛剛轉好的 ROLE_* 權限清單。

                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                // setDetails(...)：附帶請求的內容細節（例如 IP），以後若要記錄安全日誌或做風險判斷會用得到。
                SecurityContextHolder.getContext().setAuthentication(authentication);
                // 告訴 Spring Security：「這個請求已登入，而且擁有 ROLE_USER 等權限」。

            } catch (JWTVerificationException ex) {
                // token 壞掉 / 過期 / 簽章錯 → 清空身分，交給後面規則擋（由 SecurityConfig 回 401 JSON）
                log.debug("JWT verification failed: {}", ex.getMessage());
                SecurityContextHolder.clearContext();
            } catch (IllegalArgumentException ex) {
                // 例如取 claim 轉型問題等 → 清空身分
                log.debug("JWT parsing error: {}", ex.getMessage());
                SecurityContextHolder.clearContext();
            }
        }

        chain.doFilter(request, response); // 由很多 Filter 串成的關卡隊伍
        // 把請求傳給下一個 Filter；如果這個已經是最後一關，就會進到 DispatcherServlet → Controller。
        // 呼叫 chain.doFilter(...) 之後，後面的關卡（例如 Spring Security 規則、MVC、Controller）才看得到剛放進 SecurityContext 的身分。
    }
}
