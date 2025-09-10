package com.github.rdsc.dev.ProSync.repository;

import com.github.rdsc.dev.ProSync.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

// @Repository // Repository 只是一個介面，Spring Data 會自動實作。
public interface UserRepository extends JpaRepository<User, Long> { // 告訴 Spring 這是 users 表的「操作器」，主鍵型別是 Long

    // 用 email 找使用者 (找不到回傳 Optional.empty())
    Optional<User> findByEmail(String email);

    // 檢查某個email是否存在(註冊前先用這個)
    boolean existsByEmail(String email);

}
