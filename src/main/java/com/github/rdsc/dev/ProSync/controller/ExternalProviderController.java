package com.github.rdsc.dev.ProSync.controller;

import com.github.rdsc.dev.ProSync.crypto.QuoteRegistry;
import com.github.rdsc.dev.ProSync.dto.PaymentDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/external")
@RequiredArgsConstructor
@Slf4j
public class ExternalProviderController {

    private final QuoteRegistry quotesRegis;

    @PreAuthorize("hasAnyRole('ADMIN','EXTERNAL_PROVIDER')")
    @GetMapping("/ping")
    public ResponseEntity<Map<String, Object>> ping() {
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "roleRequired", "ROLE_EXTERNAL_PROVIDER",
                "timestamp", Instant.now().toString()
        ));
    }

    // 建/改報價
    @PreAuthorize("hasRole('EXTERNAL_PROVIDER')")
    @CacheEvict(
            cacheNames = "quotes",
            key = "#req.asset.trim().toUpperCase()",
            condition = "#req != null && #req.asset != null && !#req.asset.isBlank()"
    )
    @PostMapping("/quotes/post")
    public ResponseEntity<Map<String, Object>> putQuote(@RequestBody @Valid PaymentDto.QuoteRequest req) {
        BigDecimal saved = quotesRegis.put(req.getAsset(), req.getPrice());
        log.info("External quote set: {} = {} {}", req.getAsset(), saved, quotesRegis.getBaseCurrency());
        return ResponseEntity.ok(Map.of(
                "asset", req.getAsset().trim().toUpperCase(),
                "quote", saved,
                "base", quotesRegis.getBaseCurrency()
        ));
    }

    // 查報價
    @PreAuthorize("hasRole('EXTERNAL_PROVIDER')")
    @GetMapping("/quotes/get/{asset}")
    public ResponseEntity<Map<String, Object>> getQuote(@PathVariable("asset") String asset) {
        BigDecimal q = quotesRegis.get(asset)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No quote for asset: " + asset));
        return ResponseEntity.ok(Map.of(
                "asset", asset.trim().toUpperCase(),
                "quote", q,
                "base", quotesRegis.getBaseCurrency()
        ));
    }

    @PreAuthorize("hasRole('EXTERNAL_PROVIDER')")
    @GetMapping("/quotes/list-all")
    public ResponseEntity<Map<String, Object>> listAllQuotes() {
        var snap = quotesRegis.snapshot(); // Map<String, BigDecimal>（資產 → 價格）
        return ResponseEntity.ok(Map.of(
                "base", quotesRegis.getBaseCurrency(), // 一律 TWD
                "count", snap.size(),
                "quotes", snap                     // 例：{"BTC":2500000,"ETH":90000}
        ));
    }

    // 列出目前在 QuoteRegistry 裡的所有即時報價（看得到就代表 ExternalQuoteJob / 外部上報有寫進來）
    @PreAuthorize("hasAnyRole('ADMIN','EXTERNAL_PROVIDER')")
    @GetMapping("/list-all")
    public Map<String, BigDecimal> listAll() {
        return quotesRegis.snapshot();
    }

    // 刪除報價
    @PreAuthorize("hasRole('EXTERNAL_PROVIDER')")
    @CacheEvict(
            cacheNames = "quotes",
            key = "#asset.trim().toUpperCase()",
            condition = "#asset != null && !#asset.isBlank()"
    )
    @DeleteMapping("/quotes/delete/{asset}")
    public ResponseEntity<Void> deleteQuote(@PathVariable("asset") String asset) {
        quotesRegis.remove(asset);
        log.info("External quote removed: {}", asset);
        return ResponseEntity.noContent().build();
    }

}