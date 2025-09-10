# ProSync / EventHub – 後端（Spring Boot）

**目標**：跑通「活動/票種 → 下單保留 → 加密幣報價 → 付款確認」  
**原則**：一次只改 1 檔，改完就跑/測一下 

---

## 0) 需求
- JDK 17+
- Docker（啟 MySQL/Redis）
- Maven（專案已含 `./mvnw`）

---

## 1) 啟外部服務

### dev（MySQL 用 3308、Redis 6379）
```bash
# MySQL 8（DB=ProSync，root 密碼=my-secret-pw）
docker run -d --name mysql-dev \
	-e MYSQL_ROOT_PASSWORD=my-secret-pw \
	-e MYSQL_DATABASE=ProSync \
	-p 3308:3306 mysql:8

# Redis 7
docker run -d --name redis-dev -p 6379:6379 redis:7-alpine
```
### prod（ex：MySQL 用 3307）

```bash
docker run -d --name mysql-prod \   
	-e MYSQL_ROOT_PASSWORD=please-change-me \   
	-e MYSQL_DATABASE=ProSync \   
	-p 3307:3306 mysql:8
```


---

## 2) 設定檔

- `application.yml`

    - `spring.profiles.active: dev`

    - `spring.redis.*`（不是 `spring.data.redis`）

    - 自訂參數放 **根層級** `app.*`

    - 日誌用 `logback-spring.xml` → `logs/app.log`（每日封存）

- `application-dev.yml`（目前使用）

    - MySQL：`jdbc:mysql://localhost:3308/ProSync`

    - Redis：`spring.redis.host/port/database/timeout`

    - 可用環境變數覆寫：`DB_USERNAME/DB_PASSWORD/REDIS_HOST/REDIS_PORT`

- `application-prod.yml`

    - MySQL：`jdbc:mysql://localhost:3307/ProSync`

    - `spring.jpa.hibernate.ddl-auto: validate`

    - 其餘同 dev

- `app.jobs.*`

    - `app.jobs.payments-expire.*`

    - `app.jobs.restock-orders.*`

    - `app.jobs.verification.*`

    - `app.jobs.locks-purge.*`


---

## 3) 建表（Flyway）

`./mvnw -DskipTests flyway:migrate`

migrations 目錄：

- `V1__init_users.sql`

- `V2__init_roles.sql`

- `V3__seed_roles.sql`

- `V4__event_ticket_tables.sql`

- `V5__order_tables.sql`

- `V6__payments_crypto.sql`


> 若以前讓 JPA 先建過表，建議用乾淨 DB 或先 `DROP` 再 migrate；或開 `spring.flyway.baseline-on-migrate: true` 後自行比對差異。

---

## 4) 啟動

```bash
# 開發模式 
./mvnw spring-boot:run 
 
# 或打包再跑 
./mvnw -DskipTests package 
java -jar target/ProSync-0.0.1-SNAPSHOT.jar
```

啟動後看 `logs/app.log` 是否出現 log（會帶 `requestId/userId`）。

---

## 5) 身分驗證

多數 API 需要 **USER** 角色；管理端需要 **ADMIN**

```bash
# 註冊 
curl -X POST http://localhost:8080/api/user/register \   
	-H "Content-Type: application/json" \   
	-d '{"email":"user1@example.com","password":"pass123"}' 

# 驗證碼驗證
curl -X POST http://localhost:8080/public-user-controller/verify \
	-H "Content-Type: application/json" \ 
	-d '{"email":"user1@example.com","code": "981658"}'

# 登入（取得 JWT） 
curl -X POST http://localhost:8080/api/user/login \   
	-H "Content-Type: application/json" \   
	-d '{"email":"user1@example.com","password":"pass123"}' 

# 之後帶：-H "Authorization: Bearer <token>"
```

維護管理端需要 **ADMIN**、**ORGANIZER**、**EXTERNAL PROVIDER**

```sql
-- 刪除 user 權限
DELETE FROM user_roles
WHERE user_id = (SELECT u.id FROM users u WHERE u.email = 'extpro1@example.com')
AND role_id = 1;

-- 設定 特定授權 權限
INSERT INTO user_roles(user_id, role_id)
SELECT u.id, 4 FROM users u
WHERE u. email = 'extpro1@example.com'
```

---

## 6) 最小流程（保留 → 報價 → 確認）

### 6.1 建活動 + 票種（ADMIN）

```bash
# 建活動 
curl -X POST http://localhost:8080/api/events/ \   
	-H "Authorization: Bearer <ADMIN_TOKEN>" \   
	-H "Content-Type: application/json" \   
	-d '{"name":"Concert","description":"DEMO","startTime":"2025-12-31T19:30:00","endTime":"2025-12-31T21:30:00"}'  

# 建票種（eventId=1，售價 1000，庫存 10） 
curl -X POST http://localhost:8080/api/tickets \   
	-H "Authorization: Bearer <ADMIN_TOKEN>" \   
	-H "Content-Type: application/json" \   
	-d '{"eventId":1,"name":"Standard","price":1000.00,"quota":10}'
```

### 6.2 新建訂單（USER）

```bash
curl -X POST http://localhost:8080/api/public/orders \   
	-H "Authorization: Bearer <USER_TOKEN>" \   
	-H "Content-Type: application/json" \   
	-d '{"orderListId": 0,"ticketTypeId": 0,"quantity": 1}' 
# 回傳 orderListId (未輸入orderListId，回傳新的 orderListId)
# 內部：Redis 短鎖 + JPA @Version（雙保護）
```

### 6.3 加密幣付款（報價 → 確認）

```bash
# 看即時報價 
curl -X GET http://localhost:8080/api/public/payment/quote/BTC \  
	-H "Authorization: Bearer <USER_TOKEN>"  

# 建一筆 PENDING 報價（存到 payments） 
curl -X POST http://localhost:8080/api/public/payment/create-quote \   
	-H "Authorization: Bearer <USER_TOKEN>" \   
	-H "Content-Type: application/json" \   
	-d '{"orderListId":1,"asset":"BTC"}' 

# 回傳 paymentId, amountCrypto, expiresAt  

# 產一個假的 txHash（本地測試用） 
curl -X GET http://localhost:8080/api/public/payment/generate-txhash \   
	-H "Authorization: Bearer <USER_TOKEN>"  

# 確認付款（寫入 txHash → payment=CONFIRMED → 訂單=CONFIRMED） 
curl -X POST http://localhost:8080/api/public/payment/confirm-payment/<paymentId> \   
	-H "Authorization: Bearer <USER_TOKEN>" \   
	-H "Content-Type: application/json" \   
	-d '{"txHash":"0x..."}'
```

---

## 7) 排程

- `ExpiredOrderJob`：把 `PENDING_PAYMENT` 且已過期的訂單回補庫存

- `ExpiredPaymentJob`：把 `PENDING` 且 `expires_at` 已過期的付款標為 `EXPIRED`

- 啟用：主程式 `@EnableScheduling`，時間由 `app.jobs.*` 控制


---

## 8) 日誌

- 檔案：`logs/app.log`；每天封存 `app.YYYY-MM-DD.log.gz`（保留 14 天）

- `aspect/LoggingAspect`：自動加 `requestId / userId(email)` 到 MDC


---

## 9) 常見問題


-  **400** ：驗證失敗、參數錯  
   `MethodArgumentNotValidException`、`IllegalArgumentException`、`ConstraintViolationException`、`HttpMessageNotReadableException`
-  **401** ：未登入/登入錯  
   `BadCredentialsException`、`UsernameNotFoundException`

-  **403** ：沒權限  
   `AccessDeniedException`

-  **409** ：狀態衝突（庫存不足、已過期）、樂觀鎖衝突  
   `DataIntegrityViolationException`、`ObjectOptimisticLockingFailureException`、`OptimisticLockException`、`IllegalStateException`

-  **429** ：分散式鎖失敗  
   `ResponseStatusException`

-  **500**：其他未預期  
   `Exception`

- **Flyway 衝突**：舊表先清掉或用乾淨 DB，再 `flyway:migrate`

- **Redis 連線**：確認 `spring.redis.*` 與容器 port（6379），沒跑起來鎖會失敗
---
## API 路徑對照表

### public-user-controller
| 方法   | 路徑                  | 需要角色   | 說明        |
| ---- | ------------------- | ------ | --------- |
| POST | /api/users/register | PUBLIC | 註冊        |
| POST | /api/users/login    | PUBLIC | 登入（取 JWT） |
| POST | /api/users/verify   | PUBLIC | 驗證（如信箱/碼） |
| GET  | /api/users/is-me    | USER   | 取得自己的基本資料 |

---

### public-event-controller
| 方法  | 路徑                                      | 需要角色 | 說明         |
| --- | --------------------------------------- | ---- | ---------- |
| GET | /api/public/events/ping                 | USER | 健康檢查       |
| GET | /api/public/events/page                 | USER | 公開查詢活動（分頁） |
| GET | /api/public/events/find-one/{id}        | USER | 取得單一活動     |
| GET | /api/public/events/ticket/by-event/{id} | USER | 依活動列出票種    |

---

### event-controller（主辦端 / 管理端）
| 方法     | 路徑                                              | 需要角色  | 說明           |
| ------ | ----------------------------------------------- | ----- | ------------ |
| GET    | /api/events/ping                                | AUTH  | 健康檢查         |
| POST   | /api/events/organizer/create                    | ORG   | 建立活動         |
| PUT    | /api/events/organizer/update/{id}               | ORG   | 更新活動         |
| DELETE | /api/events/organizer/delete/{id}               | ORG   | 刪除活動         |
| GET    | /api/events/organizer/page                      | ORG   | 主辦者自己的活動（分頁） |
| GET    | /api/events/organizer/find-one/{id}             | ORG   | 主辦者讀單一活動     |
| POST   | /api/events/organizer/co-organizers/add/{id}    | ORG   | 新增協同主辦       |
| DELETE | /api/events/organizer/co-organizers/remove/{id} | ORG   | 移除協同主辦       |
| GET    | /api/events/admin/page                          | ADMIN | 管理端查活動（分頁）   |
| GET    | /api/events/admin/list-all                      | ADMIN | 管理端查全部       |

---

### tickets-controller（主辦端 / 管理端）
| 方法     | 路徑                                   | 需要角色  | 說明         |
| ------ | ------------------------------------ | ----- | ---------- |
| GET    | /api/tickets/ping                    | AUTH  | 健康檢查       |
| POST   | /api/tickets/organizer/create        | ORG   | 建立票種       |
| POST   | /api/tickets/organizer/update/{id}   | ORG   | 更新票種       |
| DELETE | /api/tickets/organizer/delete/{id}   | ORG   | 刪除票種       |
| GET    | /api/tickets/organizer/page          | ORG   | 主辦者的票種（分頁） |
| GET    | /api/tickets/organizer/find-one/{id} | ORG   | 主辦者讀單一票種   |
| GET    | /api/tickets/organizer/by-event/{id} | ORG   | 主辦者依活動查票種  |
| GET    | /api/tickets/admin/page              | ADMIN | 管理端票種（分頁）  |
| GET    | /api/tickets/admin/by-event/{id}     | ADMIN | 管理端依活動查票種  |

---

### public-order-controller
| 方法 | 路徑 | 需要角色 | 說明 |
|---|---|---|---|
| GET  | /api/public/orders/ping | USER | 健康檢查 |
| GET  | /api/public/orders/my | USER | 自己的訂單 |
| POST | /api/public/orders/create-order | USER | 建立空訂單（狀態 PENDING_PAYMENT） |
| POST | /api/public/orders/pay-order/{id} | USER | 對訂單付款（流程會呼叫 Payment） |

---

### public-payment-controller
| 方法 | 路徑 | 需要角色 | 說明 |
|---|---|---|---|
| GET  | /api/public/payment/quote/{asset} | USER | 即時報價（不落 DB） |
| POST | /api/public/payment/create-quote | USER | 新增一筆 `payments`（PENDING） |
| POST | /api/public/payment/confirm-payment/{id} | USER | 確認付款（填 `txHash`、訂單轉 CONFIRMED） |
| GET  | /api/public/payment/generate-txhash | USER | 產生假 `txHash`（本地測試） |

---

### external-provider-controller
| 方法     | 路徑                                  | 需要角色 | 說明       |
| ------ | ----------------------------------- | ---- | -------- |
| GET    | /api/external/ping                  | EXT  | 健康檢查     |
| POST   | /api/external/quotes/post           | EXT  | 外部匯率來源上報 |
| GET    | /api/external/quotes/list-all       | EXT  | 查看所有外部報價 |
| GET    | /api/external/quotes/get/{asset}    | EXT  | 讀單一資產報價  |
| DELETE | /api/external/quotes/delete/{asset} | EXT  | 刪除某資產報價  |

---

### admin-controller / admin-role-controller / admin-order-controller
| 方法  | 路徑                                  | 需要角色  | 說明                   |
| --- | ----------------------------------- | ----- | -------------------- |
| GET | /api/admin/ping                     | ADMIN | 健康檢查                 |
| PUT | /api/admin/users/status/update/{id} | ADMIN | 調整使用者狀態（如 SUSPENDED） |
| GET | /api/admin/roles/list               | ADMIN | 列出角色                 |
| GET | /api/admin/orders/ping              | ADMIN | 訂單管理健康檢查             |
| GET | /api/admin/orders/by-status         | ADMIN | 依狀態查訂單               |
