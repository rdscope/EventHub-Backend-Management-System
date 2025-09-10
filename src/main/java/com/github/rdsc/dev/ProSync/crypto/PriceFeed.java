package com.github.rdsc.dev.ProSync.crypto;


import java.math.BigDecimal;

/**
 * 取得加密貨幣的即時報價（1 單位 asset = ? 基準法幣）。
 * 例：asset=BTC，回傳 2500000.00000000 代表 1 BTC = 2,500,000 TWD。
**/
public interface PriceFeed {

    /**
     * 取得報價（單位：baseCurrency）。
     * @param asset 幣別（建議全大寫，如 "BTC", "ETH"）
     * @return 1 asset 等於多少 baseCurrency（BigDecimal，請用 DECIMAL 精度計算）
    **/
    public abstract BigDecimal getQuote(String asset);

    /**
     * 報價的基準法幣（例如 "TWD" 或 "USD"），用來說明 getQuote 的單位。
    **/
    public abstract String getBaseCurrency();
}
