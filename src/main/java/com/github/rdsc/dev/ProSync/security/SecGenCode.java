package com.github.rdsc.dev.ProSync.security;


import com.github.rdsc.dev.ProSync.enums.UserStatus;
import com.github.rdsc.dev.ProSync.model.User;
import com.github.rdsc.dev.ProSync.repository.UserRepository;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;


@Service
@RequiredArgsConstructor
@Slf4j
public class SecGenCode {

    private final UserRepository userRepo;

    // 預設驗證碼有效秒數（1 分鐘）
    public static final long DEFAULT_CODE_TTL_SECONDS = 60;
    // 預設延遲秒數（10 秒）
    public static final long DEFAULT_CODE_DL_SECONDS = 10;

    // email -> 驗證碼資訊（碼 + 到期時間）
    private static final ConcurrentMap<String, CodeEntry> CODES = new ConcurrentHashMap<>();

    @Getter
    @AllArgsConstructor
    private static class CodeEntry {
        private final String code;
        private final Instant expiresAt;
    }

    private static String genCode() {
        SecureRandom RANDOM = new SecureRandom();
        int n  = RANDOM.nextInt(900_000) + 100_000; // 100000 to 999999
        return String.valueOf(n);
    }

    // 1/ 延遲 N 秒後才產生驗證碼
    public String issueCodeAfterDelay(String email, Duration delay, long ttlSeconds) {

        if (delay == null || delay.isNegative()) delay = Duration.ZERO;
        long sec = delay.getSeconds();
        for (long i = sec; i >= 1; i--) {
            log.info("A verification code will be generated for {} in {} seconds...", email, i);
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("The verification code generation process was interrupted");
            }
        }
        return issueCode(email, ttlSeconds); // 倒數後一樣呼叫立即產碼
    }

    // 2/ 產生驗證碼（自訂有效秒數）
    public String issueCode(String email, long ttlSeconds) {

        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email is required");
        }

        String code = SecGenCode.genCode();

        CODES.put(email, new CodeEntry(code, Instant.now().plusSeconds(ttlSeconds)));

        log.info("Issued verify code for {}, ttl = {}s", email, ttlSeconds);

        return code;
    }

    // 3/ 驗證碼是否有效（存在 + 未過期 + 碼相同）
    private boolean isValid(String email, String input) {

        CodeEntry entry = CODES.get(email);

        if (entry == null) return false;

        if (Instant.now().isAfter(entry.getExpiresAt())) {
            CODES.remove(email); // 過期順便清掉
            return false;
        }

        return entry.getCode().equals(input);
   }

    // 4/ 驗證 + 將使用者狀態改成 ACTIVE（成功回 true；失敗回 false）
    public boolean verifyAndActivate(String email, String code) {

        if (!isValid(email, code)) return false;

        User u = userRepo.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + email));

        if (u.getStatus() != UserStatus.PENDING_VERIFICATION) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "status is " + u.getStatus() + ", cannot verify");
        }

        u.setStatus(UserStatus.ACTIVE);
        userRepo.save(u);

        CODES.remove(email);

        log.info("User {} verified and activated.", email);

        return true;
    }

    @Scheduled(
            fixedDelayString = "${app.jobs.verification.purge-delay-ms:60000}",
            initialDelayString = "${app.jobs.verification.initial-delay-ms:5000}"
    )
    // -- 清理所有已過期的碼（可選：若要排程清理再呼叫它）
    public int purgeExpired() {
        Instant now = Instant.now();
        int removed = 0;

        for (var e : CODES.entrySet()) {
            if (now.isAfter(e.getValue().getExpiresAt())) {
                CODES.remove(e.getKey());
                removed++;
            }
        }

        if (removed > 0) {
            log.info("Verification codes purged: {}", removed);
        }
        return removed;
    }

//    // 查剩餘秒數（給前端倒數用；沒有或過期就回 empty）
//    public Optional<Long> secondsLeft(String email) {
//        CodeEntry entry = CODES.get(email);
//        if (entry == null) return Optional.empty();
//        long left = entry.getExpiresAt().getEpochSecond() - Instant.now().getEpochSecond();
//        if (left <= 0) {
//            CODES.remove(email);
//            return Optional.empty();
//        }
//        return Optional.of(left);
//    }


}
