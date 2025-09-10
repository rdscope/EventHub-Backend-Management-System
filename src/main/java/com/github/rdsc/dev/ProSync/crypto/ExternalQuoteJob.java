package com.github.rdsc.dev.ProSync.crypto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 定時從外部來源抓幣價（預設 CoinGecko），寫進 QuoteRegistry。
 * DynamicPriceFeed 會優先使用這裡的最新數字；沒有時才退回 FakePriceFeed。
 *
 * 設定（application.yml）：
 * app:
 *   external-quotes:
 *     enabled: true
 *     provider: coingecko
 *     base: TWD
 *     assets: [BTC, ETH, USDT]
 *   jobs:
 *     external-quotes:
 *       initial-delay-ms: 5000
 *       delay-ms: 60000
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExternalQuoteJob {

    private final QuoteRegistry quoteRegis;
    private final CacheManager cacheManager;
    private final WebClient.Builder webClientBuilder;

    @Value("${app.external-quotes.enabled:true}")
    private boolean enabled;

    @Value("${app.external-quotes.provider:coingecko}")
    private String provider;

    @Value("${app.external-quotes.base:TWD}")
    private String base; // 法幣（例如 TWD / USD）

    @Value("${app.external-quotes.assets:BTC,ETH,USDT}")
    private List<String> assets; // 要追的資產清單（大寫，例如 BTC/ETH/USDT）

    @CacheEvict(
            cacheNames = "quotes",
            key = "#asset.trim().toUpperCase()",
            condition = "#asset != null && !#asset.isBlank()"
    )
    // 每 N 秒抓一次。關閉功能時直接略過。
    @Scheduled(
            initialDelayString = "${app.jobs.external-quotes.initial-delay-ms:5000}",
            fixedDelayString   = "${app.jobs.external-quotes.delay-ms:60000}"
    )
    public void pull() {
        if (!enabled) {
            return;
        }
        String pv = provider.trim().toLowerCase();
        try {
            if ("coingecko".equals(pv)) {
                fetchFromCoinGecko();
            } else {
                log.warn("ExternalQuoteJob: unknown provider='{}' (skip)", provider);
            }
        } catch (Exception ex) {
            log.warn("ExternalQuoteJob error (provider={}): {}", provider, ex.getMessage());
        }
    }

    // 從 CoinGecko 取得報價（simple/price），以 base（vs_currencies）為法幣。
    private void fetchFromCoinGecko() {
        // 1/ 資產清單 & 對應的 CoinGecko ID
        Map<String,String> idMap = Map.of(
                "BTC", "bitcoin",
                "ETH", "ethereum",
                "USDT", "tether"
        );
        List<String> wanted = (assets == null || assets.isEmpty())
                ? List.of("BTC","ETH","USDT")
                : assets.stream().map(s -> s.trim().toUpperCase()).collect(Collectors.toList());

        String ids = wanted.stream()
                .map(sym -> idMap.getOrDefault(sym, ""))
                .filter(s -> !s.isBlank())
                .distinct()
                .collect(Collectors.joining(","));

        if (ids.isBlank()) {
            log.warn("ExternalQuoteJob: no valid assets after mapping; assets={}", wanted);
            return;
        }

        String vs = (base == null ? "TWD" : base).trim().toLowerCase();

        // 2/ 呼叫 API（GET https://api.coingecko.com/api/v3/simple/price?ids=...&vs_currencies=...）
        WebClient client = webClientBuilder.build();
        Map<String, Map<String, Object>> resp = client.get()
                .uri(uriBuilder -> uriBuilder
                        .scheme("https")
                        .host("api.coingecko.com")
                        .path("/api/v3/simple/price")
                        .queryParam("ids", ids)
                        .queryParam("vs_currencies", vs)
                        .build())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Map<String, Object>>>() {})
                .block();

        if (resp == null || resp.isEmpty()) {
            log.warn("ExternalQuoteJob: empty response from provider=coingecko");
            return;
        }

        // 3/ 寫入 QuoteRegistry（以資產代號大寫為 key）
        int updated = 0;
        for (String sym : wanted) {
            String cgId = idMap.get(sym);
            if (cgId == null) {
                log.debug("ExternalQuoteJob: skip unsupported asset {}", sym);
                continue;
            }
            Map<String, Object> priceMap = resp.get(cgId);
            if (priceMap == null || !priceMap.containsKey(vs)) {
                log.debug("ExternalQuoteJob: no price for {} in {}", sym, vs.toUpperCase());
                continue;
            }
            Object val = priceMap.get(vs);
            BigDecimal quote = toDecimal(val);
            if (quote == null) {
                log.debug("ExternalQuoteJob: invalid price for {}: {}", sym, val);
                continue;
            }
            // 寫進暫存（系統即時報價）
            try {
                quoteRegis.put(sym, quote);
                evictQuoteCache(sym);       // ← 清掉快取，讓新數字立刻生效
                updated++;
            } catch (Exception e) {
                log.warn("ExternalQuoteJob: failed to update quote for {}: {}", sym, e.getMessage());
            }
        }
        if (updated > 0) {
            log.info("ExternalQuoteJob: {} asset quotes updated from provider=coingecko (base={})", updated, base);
        }
    }

    private void evictQuoteCache(String asset) {
        if (cacheManager == null || asset == null) return;
        Cache cache = cacheManager.getCache("quotes");
        if (cache != null) {
            String key = asset.trim().toUpperCase();
            cache.evictIfPresent(key);
            log.debug("Cache evicted: quotes::{}", key);
        }
    }

    private static BigDecimal toDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        return new BigDecimal(Objects.toString(v));
    }
}
