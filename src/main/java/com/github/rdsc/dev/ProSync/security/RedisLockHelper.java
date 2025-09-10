package com.github.rdsc.dev.ProSync.security;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;


@Component
@RequiredArgsConstructor
@Slf4j
public class RedisLockHelper {

    private static final String SEAT_PREFIX = "seat:"; // 鎖的 Key 長相會是 seat:{ticketTypeId}

    private final StringRedisTemplate redis; // 注入的 Redis 客戶端，用來 get/set/execute script

    // Lua 腳本：只有「持有同一把 token 的人」才允許刪除該 key（避免把別人的鎖刪掉）
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " + // 先把 Redis 目前這個 key 的值取出來，檢查是不是跟傳進來的 token 一樣
                    "  return redis.call('del', KEYS[1]) " + // 一樣才真的 DEL（刪掉鎖）
                    "else " +
                    "  return 0 " + // 不一樣就什麼都不做，回 0
                    "end",
            Long.class // 回傳型別 Long.class，因為 DEL 回 1/0（成功刪/沒有刪）
    );
    private static final ConcurrentMap<String, Entry> LOCKS = new ConcurrentHashMap<>();

    @Getter
    @AllArgsConstructor
    private static class Entry {
        private final String token;
        private final Instant expiresAt;
    }

    // 為「某票種」加短鎖；成功回 token，失敗回 null
    public String lockSeat (Long ticketTypeId, Duration ttl) {
        if (ticketTypeId == null) throw new IllegalArgumentException("ticketTypeId is required");
        String key = seatKey(ticketTypeId);
        return lockKey(key, ttl);
    }

    // 釋放「某票種」的鎖（只有持有相同 token 才會成功）
    public void unlockSeat(Long ticketTypeId, String token) {
        if (ticketTypeId == null) throw new IllegalArgumentException("ticketTypeId is required");
        String key = seatKey(ticketTypeId);
        unlockKey(key, token);
    }

    private String seatKey(Long ticketTypeId) {
        return SEAT_PREFIX + ticketTypeId;
    }

    // LockKey 把 seatKey(...)（字串 Key）跟 Entry（token+到期時間）「配成一對」放進 Map
    private String lockKey(String key, Duration ttl) {
        // 成功才會寫入 token；失敗表示已被別人鎖住
        Duration setTtl = (ttl == null || ttl.isZero() || ttl.isNegative())
                ? Duration.ofSeconds(5) : ttl;

        String token = UUID.randomUUID().toString();
        try {
            Boolean ok = redis.opsForValue().setIfAbsent(key, token, setTtl);
            // SET key value NX EX ttl：NX：key 不存在才設（搶鎖），EX ttl：設定過期秒數（到期自動釋放）
            // 成功回 true，代表搶到鎖；失敗回 false（別人先鎖了）
            if (Boolean.TRUE.equals(ok)) {
                // 取得鎖
                return token;
            }
            // 鎖已被別人拿走
            return null;
        } catch (Exception e) {
            log.warn("Redis lock error on key={} : {}", key, e.getMessage());
            return null;
            // 如果 Redis 連不上或出錯，記一條警告，回 null（保守做法：視為沒鎖到，避免整個流程掛掉）
        }
//        Duration setTtl = (ttl == null || ttl.isNegative() || ttl.isZero()) ? Duration.ofSeconds(5) : ttl;
//        Instant now = Instant.now();
//        Instant exp = now.plus(setTtl);
//        String token = UUID.randomUUID().toString();
//
//        Entry newEntry = new Entry(token, exp);
//
//        LOCKS.compute(key, (k, cur) -> {
//            if (cur == null || cur.expiresAt.isBefore(now)) {
//                return newEntry;
//            }
//            return cur;
//        });
//
//        Entry stored = LOCKS.get(key);
//        boolean ok = (stored != null && token.equals(stored.getToken()));
//        return ok ? token : null;
    }

    private void unlockKey(String key, String token) {
        // 比對 token 正確才刪；回傳 1 代表真的刪掉，0 代表沒刪（token 不符或 key 不存在）
        if (token == null || token.isBlank()) return;
        try {
            Long res = redis.execute(UNLOCK_SCRIPT, Collections.singletonList(key), token);
            // KEYS[1] = key  ARGV[1] = token   java.util.Arrays.asList(key) / java.util.List.of(key)
            // 腳本會先 GET 檢查值是否等於 token；一樣才 DEL
            if (res != null && res > 0) {
                log.debug("unlock ok, key={}", key);
            } else {
                log.debug("unlock skipped (token mismatch or no key), key={}", key);
                // 0 代表沒刪（可能 token 不對或鎖已過期/不存在）
            }
        } catch (Exception e) {
            log.warn("Redis unlock error on key={} : {}", key, e.getMessage());
        }
//        if (token == null || token.isBlank()) return; // 沒 token 就忽略
//
//        LOCKS.computeIfPresent(key, (k, cur) -> {
//                    // 只有持有者且未過期才移除
//                    if (token.equals(cur.token)) {
//                        return null;
//                    }
//                     return cur; // 不是同一人就不動
//        });
    }

    // 週期性清理過期鎖，避免長時間運行造成堆積（可調整頻率）
    @Scheduled(
            fixedDelayString = "${app.jobs.locks-purge.delay-ms:60000}",
            initialDelayString = "${app.jobs.locks-purge.initial-delay-ms:10000}"
    )
    public int purgeExpired() {
        Instant now = Instant.now();
        int removed = 0;

        for(var e : LOCKS.entrySet()) {
            if (e.getValue().expiresAt.isBefore(now)) {
                LOCKS.remove(e.getKey(), e.getValue());
                removed++;
            }
        }
//        var it = LOCKS.entrySet().iterator();
//        while (it.hasNext()) {
//            var e = it.next();
//            if (e.getValue().expiresAt.isBefore(now)) {
//                it.remove();
//                removed++;
//            }
//        }
        if (removed > 0) {
            log.info("RedisLockHelper purge: {} expired locks removed", removed);
        }
        return removed;
    }

}
