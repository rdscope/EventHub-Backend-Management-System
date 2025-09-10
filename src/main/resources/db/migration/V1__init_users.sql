CREATE TABLE IF NOT EXISTS users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(191) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    create_at DATETIME(6) NOT NULL,
    update_at DATETIME(6) NOT NULL,
    failed_login_count INT NOT NULL DEFAULT 0,

    CONSTRAINT pk_users PRIMARY KEY (id), -- primary_key：主鍵＝必唯一、必不空，DB 會做索引
    CONSTRAINT uk_users_email UNIQUE (email) -- unique_key：唯一鍵＝值不能重覆（email 也設定不空）
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4; -- ENGINE = InnoDB 存放資料的引擎：有「交易」(可 commit/rollback)、
                                            --                               有「外鍵」(可做表間關聯)、
                                            --                               有「列級鎖」(多人同時操作較安全) 下單、鎖庫存