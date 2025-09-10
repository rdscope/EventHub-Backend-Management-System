-- 加密支付：一筆Payment對應一張order

CREATE TABLE payments (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_list_id BIGINT NOT NULL,
    asset VARCHAR(20) NOT NULL,              -- 幣別：BTC、ETH...（全部大寫慣例）
    quote_rate DECIMAL(18, 8) NOT NULL,      -- 報價：1 單位 asset 等於多少法幣（例如 TWD/USD）
    amount_crypto DECIMAL(38, 18) NOT NULL,  -- 這筆要付多少加密貨幣（高精度）
    tx_hash VARCHAR(128) NULL,               -- 鏈上交易 hash（建立付款前可為 NULL；確認後填）
    status VARCHAR(20) NOT NULL,             -- 狀態：PENDING / CONFIRMED / EXPIRED / FAILED
    expires_at DATETIME(6) NOT NULL,         -- 報價/付款逾期時間（用來定時掃過期）
    create_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_payments_dep_order_list_id FOREIGN KEY (order_list_id) REFERENCES order_list(id) ON DELETE CASCADE,
    CONSTRAINT uk_payments_tx_hash UNIQUE (tx_hash),

    INDEX idx_payments_by_order_list_id (order_list_id),
    INDEX idx_payments_by_expires_at (expires_at),
    INDEX idx_payments_by_status (status)

)ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 也可以寫在外面 ->
-- CREATE INDEX idx_payments_status ON payments(status);
-- 或
-- ALTER TABLE payments ADD INDEX idx_payments_status (status);