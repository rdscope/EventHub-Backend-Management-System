-- ORDER LIST
CREATE TABLE IF NOT EXISTS order_list (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    total_cost DECIMAL(18,2)  NOT NULL DEFAULT 0.00,
    expires_at DATETIME(6) NULL, -- 「等付款」的訂單才需要截止時間，一旦付款成功或取消，這張訂單不再會逾時
    create_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_order_list_dep_user_id FOREIGN KEY (user_id) REFERENCES users(id)
)ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_order_list_by_status ON order_list(status);
CREATE INDEX idx_order_list_by_expires_at ON order_list(expires_at);

-- ORDER DETAIL
CREATE TABLE IF NOT EXISTS order_detail (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_list_id BIGINT NOT NULL,
    ticket_type_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    unit_price DECIMAL(18,2) NOT NULL,
    cost DECIMAL(18,2) NOT NULL,           -- 小計 = quantity * unit_price
    create_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_order_detail_dep_order_list_id FOREIGN KEY (order_list_id) REFERENCES order_list(id) ON DELETE CASCADE,
    -- 我的參考對象刪，我這邊有關的資料也會刪
    CONSTRAINT fk_order_detail_str_ticket_type_id FOREIGN KEY (ticket_type_id) REFERENCES ticket_types(id) ON DELETE RESTRICT,
    -- 如果我這邊有紀錄，我的參考對象那就不准刪
    CONSTRAINT uk_order_detail_with_order_list_id_ticket_type_id UNIQUE (order_list_id, ticket_type_id)
)ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_order_detail_by_order_list_id ON order_detail(order_list_id);
CREATE INDEX idx_order_detail_by_ticket_type_id ON order_detail(ticket_type_id);