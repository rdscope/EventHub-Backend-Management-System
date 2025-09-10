package com.github.rdsc.dev.ProSync.crypto;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 先讀 QuoteRegistry（由 EXTERNAL_PROVIDER 設的即時報價），
 * 沒有時再退回 FakePriceFeed 的固定價。
**/
@Component
@Primary
public class DynamicPriceFeed implements PriceFeed {

    private final QuoteRegistry quotes;
    private final FakePriceFeed fallback;

    public DynamicPriceFeed(QuoteRegistry quotes, FakePriceFeed fallback) {
        this.quotes = quotes;
        this.fallback = fallback;
    }

    @Override
    @Cacheable(
            cacheNames = "quotes",
            key = "#asset.trim().toUpperCase()",                // BTC/ETH/USDT…一律用大寫字串當快取鍵
            condition = "#asset != null && !#asset.isBlank()"   // asset 空白就不要快取，避免 NPE
    )
    public BigDecimal getQuote(String asset) {
        // 1/ 先看外部供應商是否有塞報價
        var q = quotes.get(asset);
        if (q.isPresent()) {
            return q.get();
        }
        // 2/ 沒有就退回固定價（BTC/ETH/USDT…）
        return fallback.getQuote(asset);
    }

    @Override
    public String getBaseCurrency() {
        // 與外部供應商一致
        return quotes.getBaseCurrency();
    }
}