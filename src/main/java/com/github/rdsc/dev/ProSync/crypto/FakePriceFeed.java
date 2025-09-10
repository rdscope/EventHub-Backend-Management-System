package com.github.rdsc.dev.ProSync.crypto;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.security.Key;
import java.util.Map;

/**
 * 假報價來源：回固定數值，方便本地與測試。
 * 單位一律為 TWD（getBaseCurrency()）。
**/
@Component
public class FakePriceFeed implements PriceFeed {

    // 簡單固定價
    private static final Map<String, BigDecimal> QUOTES_TWD = Map.of(
            "BTC", new BigDecimal("2500000.00000000"),
            "ETH", new BigDecimal("90000.00000000"),
            "USDT", new BigDecimal("32.50000000")

    );

    @Override
    public BigDecimal getQuote(String asset) {

        if (asset == null || asset.isBlank()) {
            throw new IllegalArgumentException("asset cannot be blank");
        }

        String key = asset.trim().toUpperCase();
        // 找不到就回一個保守值，避免 NPE，或丟出例外
        BigDecimal quote = QUOTES_TWD.get(key);

        if (quote == null) {
            throw new IllegalArgumentException("unsupported asset: " + asset);
        }
        return quote;
    }

    @Override
    public String getBaseCurrency() {
        return "TWD";
    }


}
