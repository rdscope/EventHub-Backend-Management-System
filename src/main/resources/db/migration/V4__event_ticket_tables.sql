-- 重點：price 用 DECIMAL(18,2)、(event_id,name) 唯一鍵、外鍵連到 events

-- EVENTS
CREATE TABLE IF NOT EXISTS events (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(200) NOT NULL,
    description VARCHAR(500) NULL,
    start_time DATETIME(6) NOT NULL,
    end_time DATETIME(6) NOT NULL,
    create_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
)ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 常用查詢：依日期查活動 -- 要加索引，讓查詢更快
CREATE INDEX idx_events_by_start_time ON events(start_time); -- 在 events 表的 start_time 欄位上建立索引

-- TICKET TYPES
CREATE TABLE IF NOT EXISTS ticket_types(
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    price DECIMAL(18,2) NOT NULL,
    quota INT NOT NULL, -- 庫存
    version BIGINT NOT NULL DEFAULT 0, -- 多人同時買票時，透過版本比對，避免「同一張票種被超賣」。
                                       -- 樂觀鎖：對應JPA的 @Version
    create_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_ticket_types_dep_event_id FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE,
    CONSTRAINT uk_ticket_types_name_with_event_id UNIQUE (name, event_id)
)ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 常用查詢：依活動查票種
CREATE INDEX idx_ticket_types_by_event_id ON ticket_types(event_id); -- 某活動的所有票種

-- 主要主辦人以外的「協同主辦人」關聯表
CREATE TABLE IF NOT EXISTS event_organizers (
  event_id BIGINT NOT NULL,
  user_id  BIGINT NOT NULL,
  PRIMARY KEY (event_id, user_id),
  CONSTRAINT fk_event_organizers_dep_event_id FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE, -- 刪活動時自動清掉關聯
  CONSTRAINT fk_event_organizers_dep_user_id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT -- 不能刪還在協辦中的使用者
);