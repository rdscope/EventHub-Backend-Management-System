package com.github.rdsc.dev.ProSync.crypto;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class QuoteRegistry {

    private static final String BASE = "TWD";
    private final ConcurrentMap<String, BigDecimal> quotes = new ConcurrentHashMap<>();

    // 基礎幣別（目前固定 TWD）
    public String getBaseCurrency() {
        return BASE;
    }

    private static String normalize(String asset) {
        if (asset == null || asset.isBlank()) {
            throw new IllegalArgumentException("asset is required");
        }
        return asset.trim().toUpperCase();
    }

    private static void requirePositive(BigDecimal price) {
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("price must be > 0");
        }
    }

    // 新增或更新報價（資產代碼自動標準化成大寫，價格需 > 0）
    public BigDecimal put(String asset, BigDecimal price) {
        String key = normalize(asset);
        requirePositive(price);
        quotes.put(key, price);
        return price;
    }

    // 取得單一資產報價
    public Optional<BigDecimal> get(String asset) {
        if (asset == null) return Optional.empty();
        return Optional.ofNullable(quotes.get(normalize(asset)));
    }
//    public BigDecimal getOrThrow(String asset) {
//        return quotes.computeIfAbsent(normalize(asset), k -> {
//            throw new IllegalArgumentException("No quote for " + k);
//        });
//    }

    // 移除單一資產報價
    public void remove(String asset) {
        if (asset == null) return;
        quotes.remove(normalize(asset));
    }

    // 目前全部報價的唯讀快照（給除錯/管理用）
    public Map<String, BigDecimal> snapshot() {
        return Map.copyOf(quotes);
    }
}
