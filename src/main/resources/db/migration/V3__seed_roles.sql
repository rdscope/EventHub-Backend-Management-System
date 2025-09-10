INSERT INTO roles (name, create_at, update_at) VALUES
    ('USER', NOW(6), NOW(6)),
    ('ADMIN', NOW(6), NOW(6)),
    ('ORGANIZER', NOW(6), NOW(6)),
    ('EXTERNAL_PROVIDER', NOW(6), NOW(6))
AS new
ON DUPLICATE KEY UPDATE update_at = new.update_at; -- 想插入一筆資料，如果因為「主鍵或唯一鍵」撞到現有資料，就改成「更新那筆」