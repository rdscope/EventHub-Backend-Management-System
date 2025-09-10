package com.github.rdsc.dev.ProSync.security;

// com.auth0.jwt.*：第三方 JWT 函式庫要用到的類別（建立 token、驗證 token、解析 claim）。
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;
import com.github.rdsc.dev.ProSync.enums.UserRole;
import com.github.rdsc.dev.ProSync.model.Role;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import static org.apache.commons.lang3.ArrayUtils.toArray;


@Component
@Slf4j
// 告訴 Spring：「請把我（JwtUtil）登記成一顆 Bean（預設 singleton）。」
// 好處：在別的類別可以直接用建構子注入來取得 JwtUtil，不用自己 new。
public class JwtUtil { // 產生 Token

    private static final String DEFAULT_SECRET = "change-me-please";
    private static final String ISSUER = "ProSync";
    private static final long EXPIRE_MILLIS = 2*60*60*1000L; // 2小時
    // DEFAULT_SECRET：預設簽章密鑰，當沒有另外提供密鑰時，就會用這一串字來簽發/驗證 JWT。（只是臨時用，正式一定要改）
    // ISSUER：發行者標記，驗證時會檢查 token 來自我給的（之後驗證時會檢查是不是我發的）
    // EXPIRE_MILLIS：token 有效時間（這裡是 2 小時 = 2 小時 × 60 分 × 60 秒 × 1000 毫秒）

    private final Algorithm algorithm; // algorithm：用哪種簽章法簽 token（這裡用 HMAC256）
    private final JWTVerifier verifier; // verifier：一個可重複使用的「驗證器」，專門用來驗 token

    public JwtUtil() {
        String secret = System.getenv().getOrDefault("JWT_SECRET", DEFAULT_SECRET);
        //  這行在啟動時去「作業系統的環境變數」找 JWT_SECRET。
        //      找得到 → 用環境變數的值（比較安全，可依環境切換）。
        //              1. 安全：密鑰不寫死在程式碼，也不會被 commit 上 Git。
        //              2. 可切換：開發／測試／正式，各用不同密鑰，不用改程式。
        //              3. 12-Factor：設定與程式碼分離的標準做法。
        //      找不到 → 退回用上面的 DEFAULT_SECRET（只適合本機練習）。

        if (DEFAULT_SECRET.equals(secret)) {
            // 用預設密鑰（本地開發可，正式環境應設 JWT_SECRET）
            log.warn("JWT secret is using DEFAULT value; set env var JWT_SECRET in non-dev environments.");
        } else if (secret.length() < 32) {
            // 太短也提醒（不印出內容，避免外洩）
            log.warn("JWT secret length is short (<32); consider using a stronger secret.");
        }

        this.algorithm = Algorithm.HMAC256(secret.getBytes(StandardCharsets.UTF_8));
        // 用祕鑰 建立 HMAC-SHA256 演算法
        this.verifier = JWT.require(algorithm).withIssuer(ISSUER).build();
        // 建立「驗證器」：規定 token 必須用同一把演算法簽、而且 iss（issuer）= "ProSync"，才算合法。

        // Windows CMD 臨時設定環境變數：set JWT_SECRET=your-strong-secret
        // PowerShell：$env:JWT_SECRET="your-strong-secret"
        // Git Bash/MSYS2：export JWT_SECRET='dev-very-long-random-string'

    }

    // 產生 JWT：sub = UserID，夾帶 email 與 roles
    public String generateToken(Long userId, String email, List<UserRole> roles) {
        if (userId == null) throw new IllegalArgumentException("userId is required");
        if (email == null || email.isBlank()) throw new IllegalArgumentException("email is required");

        long now = System.currentTimeMillis(); // 取現在時間 now

        String[] roleArray = (roles == null) ? new String[0] : roles.stream().map(UserRole::name).toArray(String[]::new);
        // 把 List<String> 轉成陣列（這個套件的 withArrayClaim 需要陣列）。

        return JWT.create()
                .withIssuer(ISSUER) // 建立一顆新的 JWT，設定簽發者是 ProSync。
                .withSubject(String.valueOf(userId)) //設定 sub（主體）= 使用者 ID（轉字串）。之後要找「這顆 token 是誰的」就看這裡。
                .withClaim("email", email) // 自訂欄位 email，讓前端或後端知道這顆 token 附帶哪個 email。
                .withArrayClaim("roles", roleArray) // 自訂欄位 roles，用陣列形式儲存，例如 ["USER"]、["ADMIN","USER"]。
                .withIssuedAt(new Date(now))
                .withExpiresAt(new Date(now + EXPIRE_MILLIS))
                .sign(algorithm); // 用設定好的祕鑰把 token 簽起來，得到可回傳的字串。
        // Claim = 欄位：像 email、roles、sub（使用者ID）、exp（到期時間）都叫 claim。
        // 兩種常見 claim：
        //      「標準」：iss、sub、exp、iat…（規格有定義）
        //      「自訂」：email、roles…（想塞什麼就塞什麼）
    }

    // 驗證Token(Strict)：成功回 DecodedJWT（能讀 sub、email、roles 等欄位），失敗會丟 JWTVerificationException
    public DecodedJWT verify(String token) throws JWTVerificationException {
        return verifier.verify(token);
    }

    // 驗證Token(Easy)：合法= true，否則 false（不丟例外）
    public boolean isValid(String token) {
        try {
            verifier.verify(token);
            return true;
        } catch (JWTVerificationException ex) {
            return false;
        }
    }

    // 取出Roles
    public List<String> getRoles(DecodedJWT jwt) {
        Claim c = jwt.getClaim("roles"); // 從 DecodedJWT 取 roles 這個 claim
        // Claim 就是 JWT 裡的一個「欄位」。
        // JWT 分三段：header.payload.signature，而 payload 就是一堆 claims（欄位） 組成的 JSON。
        // 程式裡的 Claim 類別：是 java-jwt 提供的「欄位包裝器」。
        // 要用 asString()、asDate()、asList(...) 把值拿出來。
        List<String> roles = c.asList(String.class);
        return roles == null ? List.of() : roles;
    }
}
