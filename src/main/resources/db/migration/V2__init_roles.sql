CREATE TABLE IF NOT EXISTS roles (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(50) NOT NULL,
    create_at DATETIME(6) NOT NULL,
    update_at DATETIME(6) NOT NULL,

    CONSTRAINT pk_roles PRIMARY KEY (id),
    CONSTRAINT uk_roles_name UNIQUE (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS user_roles (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,

    CONSTRAINT fk_user_roles_dep_user_id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE, -- 如果某個使用者被刪掉，user_roles 裡面「跟他有關的那幾筆關聯」會自動一起刪掉
    CONSTRAINT fk_user_roles_dep_role_id FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE, -- 如果某個角色被刪掉，user_roles 中「指到這個角色的關聯」會自動一起刪掉
                                                                                                -- 自動刪掉」只會發生在 關聯表 user_roles，不會連帶刪掉別的表的資料
                                                                                                -- （不會反過來刪 users 或 roles 其他資料）
    CONSTRAINT uk_user_roles_with_user_id_role_id UNIQUE (user_id, role_id) -- 同一個人同一個角色的組合 只能出現一次
                                                       -- 同一個人可以有多個不同角色（OK）。
                                                       -- 同一個角色可以給很多人（OK）。
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

