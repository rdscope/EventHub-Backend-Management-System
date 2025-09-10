package com.github.rdsc.dev.ProSync.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 超白話：
 * - 進入 Controller：幫這次請求生一個 requestId，從 SecurityContext 取 user（email），放進 MDC。
 * - 期間內所有 log（含 Service 層）都會自動帶上 requestId / userId（因為 logback pattern 已放 %X{...}）。
 * - 出來時清掉，避免髒到下一次請求。
**/
@Aspect
@Component
@Slf4j
@Order(0) // 讓它越早執行越好
public class LoggingAspect {

    // 只在 Controller 層設定/清理 MDC（覆蓋整個請求流程）
    @Around("execution(public * com.github.rdsc.dev.ProSync.controller..*(..))")
    public Object aroundController(ProceedingJoinPoint pjp) throws Throwable {
        boolean newReqId = false;
        String reqId = MDC.get("requestId");
        if (reqId == null || reqId.isBlank()) {
            reqId = UUID.randomUUID().toString().replace("-", "");
            MDC.put("requestId", reqId);
            newReqId = true;
        }

        // userId（email）
        String userId = MDC.get("userId");
        if (userId == null) {
            Authentication auth = SecurityContextHolder.getContext() != null
                    ? SecurityContextHolder.getContext().getAuthentication() : null;
            if (auth != null && auth.getName() != null) {
                MDC.put("userId", auth.getName());
            }
        }

        long start = System.currentTimeMillis();
        String sig = pjp.getSignature().toShortString();
        log.info("[ENTER] {}", sig);
        try {
            Object ret = pjp.proceed();
            long cost = System.currentTimeMillis() - start;
            log.info("[EXIT ] {} ({} ms)", sig, cost);
            return ret;
        } finally {
            // 只在這層清理，避免污染其他請求執行緒
            if (newReqId) MDC.remove("requestId");
            // userId 每次請求都重設，這裡也清
            MDC.remove("userId");
        }
    }

    // Service 層：只做進出點日志（不動 MDC，避免多次覆寫）
    @Around("execution(public * com.github.rdsc.dev.ProSync.service..*(..))")
    public Object aroundService(ProceedingJoinPoint pjp) throws Throwable {
        String sig = pjp.getSignature().toShortString();
        log.info("→ {}", sig);
        try {
            return pjp.proceed();
        } finally {
            log.info("← {}", sig);
        }
    }
}
