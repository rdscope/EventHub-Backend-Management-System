## 1. 簡介

### 1.1 目的

EventHub 是一個 **活動售票與資料聚合平台**，提供活動建立、票種管理、線上訂票、加密支付模擬，以及外部匯率資料整合。  
此系統支援高併發搶票場景，確保「不超賣」，並以 **JWT + RBAC** 管理不同角色的權限。

### 1.2 範疇

- 線上活動票券發售管理
    
- 線上售票與付款流程
    
- 匯率與外部 API 整合
    
- 高併發搶票下的交易一致性
    

---

## 2. 整體描述

### 2.1 系統架構

- **服務設計**：
    
    - `auth-service`：帳號、登入、JWT、RBAC
        
    - `event-service`：活動與票種（含庫存）
        
    - `order-service`：訂單與扣庫存
        
    - `market-service`：外部匯率整合
        
    - `notify-service`：通知（log / webhook）
        
- **基礎設施**：
    
    - MySQL（InnoDB，Flyway 管理 migration）
        
    - Redis（快取、分散式鎖、事件流）
        
    - Docker Compose（整合部署）
        

### 2.2 角色 (RBAC)

- **Admin**：管理活動、票種、使用者角色
    
- **Organizer**：建立與維護活動
    
- **User**：查詢活動、下單、付款
    
- **External Provider**：上傳外部匯率資料
    


[![](https://img.plantuml.biz/plantuml/svg/PLBBJiCm4BplLwnw2eSAqVJ90HL5bJWK4Qhs0znacreuTh2TYe3uTzpKiT9oyynuPZrQBurbsjQb011qi6WLQbuSBUPSKsQvai0ogpG-voNlpc-wO99_a_PuPy9niFgof2KJc6fruZdf0J8J000Ym3cPoK8GhauaxKkznn-8DbHmuynuZsjLS7btDkwI1uXUcMG5jMDmemGPBuqWMZlB9QKZOuXImJ9ohtG3FJc6A64o8t75JXohv87h4XFTLAvxofhIPNbzwdHDpFftUdOMs2tFFiXkcugyD0-IptklbUt41VnUwhSzXiEdjbmFn7qmxeD97qm1scM4Y3wAhz088EmgNBzbPl-OEG1q6mks6sA1ZrZLveYVvuh6EM19CdS_wGy0)](https://editor.plantuml.com/uml/PLBBJiCm4BplLwnw2eSAqVJ90HL5bJWK4Qhs0znacreuTh2TYe3uTzpKiT9oyynuPZrQBurbsjQb011qi6WLQbuSBUPSKsQvai0ogpG-voNlpc-wO99_a_PuPy9niFgof2KJc6fruZdf0J8J000Ym3cPoK8GhauaxKkznn-8DbHmuynuZsjLS7btDkwI1uXUcMG5jMDmemGPBuqWMZlB9QKZOuXImJ9ohtG3FJc6A64o8t75JXohv87h4XFTLAvxofhIPNbzwdHDpFftUdOMs2tFFiXkcugyD0-IptklbUt41VnUwhSzXiEdjbmFn7qmxeD97qm1scM4Y3wAhz088EmgNBzbPl-OEG1q6mks6sA1ZrZLveYVvuh6EM19CdS_wGy0)


---

## 3. 功能需求

### 3.1 使用案例

- Public / User：
    
    - 註冊/登入
        
    - 瀏覽活動與票種
        
    - 下單 / 搶票
        
    - 付款（加密支付）
        
- Organizer：
    
    - 建立活動與票種
        
- Admin：
    
    - 使用者狀態管理
        
    - 設定使用者角色
        
- External Provider：
    
    - 上報匯率資訊
        

### 3.2 功能模組

1. **活動管理**
    
    - CRUD 活動、票種
        
    - Redis 快取活動列表
        
2. **訂單管理**
    
    - 下單（扣庫存，防超賣）
        
    - 訂單逾時 → 自動回補庫存
        
3. **支付模組**
    
    - 加密幣報價（透過 CoinGecko API 或 FakePrice）
        
    - 報價 TTL = 15 分鐘
        
    - 確認付款後 → 訂單狀態 CONFIRMED
        
4. **外部整合**
    
    - `ExternalQuoteJob` 定期抓 API
        
    - `QuoteRegistry` 暫存匯率
        
    - ExternalProvider 手動上報
        
5. **通知模組**
    
    - 訂單成功 / 狀態變更 → log 模擬通知
        

---

## 4. 非功能性需求

### 4.1 安全性

- JWT Token 驗證
    
- RBAC：路由與方法授權
    
- GlobalExceptionHandler：統一錯誤回傳
    

### 4.2 效能

- Redis 分散式鎖（seat:{ticketTypeId}，避免多用戶同時扣同一票）
    
- JPA `@Version` 樂觀鎖（確保不超賣）
    
- 快取：活動列表 TTL 30s，匯率 TTL 60s
    

### 4.3 一致性

- MySQL ACID 交易
    
- 隔離級別：`REPEATABLE_READ`
    
- Outbox pattern：保證跨服務事件一致性
    

---

## 5. 資料模型

- **User / Role / UserRole**：RBAC 使用者與角色關聯
    
- **Event / TicketType**：活動與票種（含 quota, version）
    
- **Order / OrderDetail**：訂單與訂單明細
    
- **Payment**：付款（asset, quote_rate, tx_hash, status, expires_at）
    


[![](https://img.plantuml.biz/plantuml/svg/hLHBRzim33vFlqB8QUrQREakGL4K2P82Cv0-q6Ix3fhDTaJry2IwRLhiluzMRCTnN60PTh4faY8_afyu3gGy5KOB4HxGvieh8rEjGfWGUVnH42Ifimjy0Vb9Y6YHNmDu-IhUVPWxUoynOsd1pYo3KQWtnvozdvpFlgZmaxNblGKfAe8CySUwnB9eIQOU5C64MgeYpseLyl6EGqCsT_Tefwwm97xNU6wTXWwUCbLKU_vSAGFHcYezuv1BDD1Ek6reqZRnBnw1y_SMRFj2nvwMqeu5RDQhtmFC4jC7eEKgxvOS9IYX9hi0Pv2YKLhc7bEGPsUpYsboEPc_7uwEJWxEpwiM_oeSAPPr6UeHV41dsNdyRIr6lty6Vkqpy7CCtKv6gU1vHTZHgqkI6ZqnF3rr7AAlWawhdtFq47gWxOjv1iJKwqFjwfAIHaNDUA9ktLLcCzAgLKXXaMxU74NjbVvTfNlKSwDMPfVBFRLKxE4xG2r49K7WbeDkmnfjurQcV9zJlyh9jHm_Zuw6bMSLdfwhpL3XwixzlquqRgt1S22FZ-NWSB3XezWmisjUZrwqtjmR7kiLqJ9khbI93njJt4kdaVBVo_KK-W7KipgLccjWMyxRWxlsaVVn7Ne3cH1ZLl7o_WC0)](https://editor.plantuml.com/uml/hLHBRzim33vFlqB8QUrQREakGL4K2P82Cv0-q6Ix3fhDTaJry2IwRLhiluzMRCTnN60PTh4faY8_afyu3gGy5KOB4HxGvieh8rEjGfWGUVnH42Ifimjy0Vb9Y6YHNmDu-IhUVPWxUoynOsd1pYo3KQWtnvozdvpFlgZmaxNblGKfAe8CySUwnB9eIQOU5C64MgeYpseLyl6EGqCsT_Tefwwm97xNU6wTXWwUCbLKU_vSAGFHcYezuv1BDD1Ek6reqZRnBnw1y_SMRFj2nvwMqeu5RDQhtmFC4jC7eEKgxvOS9IYX9hi0Pv2YKLhc7bEGPsUpYsboEPc_7uwEJWxEpwiM_oeSAPPr6UeHV41dsNdyRIr6lty6Vkqpy7CCtKv6gU1vHTZHgqkI6ZqnF3rr7AAlWawhdtFq47gWxOjv1iJKwqFjwfAIHaNDUA9ktLLcCzAgLKXXaMxU74NjbVvTfNlKSwDMPfVBFRLKxE4xG2r49K7WbeDkmnfjurQcV9zJlyh9jHm_Zuw6bMSLdfwhpL3XwixzlquqRgt1S22FZ-NWSB3XezWmisjUZrwqtjmR7kiLqJ9khbI93njJt4kdaVBVo_KK-W7KipgLccjWMyxRWxlsaVVn7Ne3cH1ZLl7o_WC0)


---

## 6. 系統流程

### 6.1 搶票流程

1. 嘗試 Redis 鎖 → 搶不到 → 429 Too Many Requests
    
2. 搶到 → DB 減 quota（樂觀鎖 version++）
    
3. 若版本衝突 → 回 409 Conflict（票被別人先扣走）
    
4. 成功 → 建立 PENDING_PAYMENT 訂單
    
5. 逾時排程（ExpiredOrderJob） → 自動回補庫存
    

[![](https://img.plantuml.biz/plantuml/svg/XPDFQzmm4CNl-XH33iLXasuJsaDDigHr2TconS4Mv2oa2IjMaRpIU8k_VST_DbkJb5uCid_pzDuyV9T8HMfVd11AKuXGE8kUX6ZujTMsKPxWh8m6On4ynU0SnZDG2SfYpbHeRDe4lNj0rqnCjNcOGzBg7LADyM0r3eYitoDars25JKYMFNKZAAjtJ6rieXvS3gSVICgw_ZnQh8G046BC19ShDft3OrbBMAh6BaC_ao3x-IGl81pbrkO2wDL5VzfJTov4MVGA72a7np5hL9JDEI9t-oKngXmD32YzRsr4SvoMTGuH4-V7xByNodeOaLKEpzQRxztQF5dQbWtP5nwpkjzsPasFoWek9_tJ1hC3nxF1tnvEQWpeBRArVHj8mGSmg2EgXBFxaZhWDs_kNPks6T4vtDnCMei5Rmfe6yDlc3jPhOuI9Ikq3QSa-AiMM71qhPo6r-O-4djmuzZKJaHgen_RM4zxuqzZXJbSVxg2WltIQQT38wt_LoTXXvxZeqiuTlBCNl2jykVhho13W5_ATr3XliL4YUSyetVyv7_a3m00)](https://editor.plantuml.com/uml/XPDFQzmm4CNl-XH33iLXasuJsaDDigHr2TconS4Mv2oa2IjMaRpIU8k_VST_DbkJb5uCid_pzDuyV9T8HMfVd11AKuXGE8kUX6ZujTMsKPxWh8m6On4ynU0SnZDG2SfYpbHeRDe4lNj0rqnCjNcOGzBg7LADyM0r3eYitoDars25JKYMFNKZAAjtJ6rieXvS3gSVICgw_ZnQh8G046BC19ShDft3OrbBMAh6BaC_ao3x-IGl81pbrkO2wDL5VzfJTov4MVGA72a7np5hL9JDEI9t-oKngXmD32YzRsr4SvoMTGuH4-V7xByNodeOaLKEpzQRxztQF5dQbWtP5nwpkjzsPasFoWek9_tJ1hC3nxF1tnvEQWpeBRArVHj8mGSmg2EgXBFxaZhWDs_kNPks6T4vtDnCMei5Rmfe6yDlc3jPhOuI9Ikq3QSa-AiMM71qhPo6r-O-4djmuzZKJaHgen_RM4zxuqzZXJbSVxg2WltIQQT38wt_LoTXXvxZeqiuTlBCNl2jykVhho13W5_ATr3XliL4YUSyetVyv7_a3m00)


### 6.2 支付流程

1. 建立報價 → 若已有有效 PENDING 報價 → 回覆舊的
    
2. 確認付款（txHash 驗重） → 訂單狀態 CONFIRMED
    
3. txHash 重複 → 回 409 Conflict
    

[![](https://img.plantuml.biz/plantuml/svg/bPDDZzCm48Rl_XMZFMMbVgmuve2sJLkes6tS9XmGGiZ4iwsrYHFiIMc4zh_ZVBMavSBHpZFllE-CysX962gpb36HK6xWiqN3ME4kLQ8AeGdkUFaZLGaNLOQQWboJoTCKpHq82vm7Lt2BHMYEAi6MYOP8Q2IQ9sLfXqLkbUjQDLpuj1k2sp8dtE7UeQQQBYejCkV3EDqLecngjloVxQ-K-PLmh99N77YC8yO06AlZm_XzdSi77aOnJ4MXfaKpWcdHgayJWu9m_BFsnvXZcvB8X_P3OAg2yiQzb_TXrtBa6bcaUrSHrGKkhmylIijvjPOpBICIL5gFBpUBzUR3TpxxyhpSn6UST_HA6KlpAhm8dskRktaLD2vcj42RU9F9n4cuzQO41vVqA58beK0jbTv35w7jqUSsM5g4r6bOwWbCtPN6N_svzRJbFkoHsgZ3TBLTAuxOvna1dWfbqCvwvjAEIgEXMvsdwa5bUUdUMZF7KP_0MVwl9UNwHPbit9z_A_asN5OBj5tYazU1zQBez57OmytewqNTtiKDx7Kry5MM1IG7J5w_tSmY23UhzUvvkJXBnnSZ_G3-UZBtdM47rg5zUFVm0E4dnXxTCDnV-mS0)](https://editor.plantuml.com/uml/bPDDZzCm48Rl_XMZFMMbVgmuve2sJLkes6tS9XmGGiZ4iwsrYHFiIMc4zh_ZVBMavSBHpZFllE-CysX962gpb36HK6xWiqN3ME4kLQ8AeGdkUFaZLGaNLOQQWboJoTCKpHq82vm7Lt2BHMYEAi6MYOP8Q2IQ9sLfXqLkbUjQDLpuj1k2sp8dtE7UeQQQBYejCkV3EDqLecngjloVxQ-K-PLmh99N77YC8yO06AlZm_XzdSi77aOnJ4MXfaKpWcdHgayJWu9m_BFsnvXZcvB8X_P3OAg2yiQzb_TXrtBa6bcaUrSHrGKkhmylIijvjPOpBICIL5gFBpUBzUR3TpxxyhpSn6UST_HA6KlpAhm8dskRktaLD2vcj42RU9F9n4cuzQO41vVqA58beK0jbTv35w7jqUSsM5g4r6bOwWbCtPN6N_svzRJbFkoHsgZ3TBLTAuxOvna1dWfbqCvwvjAEIgEXMvsdwa5bUUdUMZF7KP_0MVwl9UNwHPbit9z_A_asN5OBj5tYazU1zQBez57OmytewqNTtiKDx7Kry5MM1IG7J5w_tSmY23UhzUvvkJXBnnSZ_G3-UZBtdM47rg5zUFVm0E4dnXxTCDnV-mS0)


---

## 7. 錯誤碼規範

- `400`：參數驗證失敗
    
- `401`：未登入 / 登入失敗
    
- `403`：權限不足
    
- `409`：資料衝突（庫存不足、已過期）
    
- `429`：太多請求（鎖被佔用）
    
- `500`：未知錯誤
    
