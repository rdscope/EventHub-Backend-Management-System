package com.github.rdsc.dev.ProSync.service;

import com.github.rdsc.dev.ProSync.enums.UserRole;
import com.github.rdsc.dev.ProSync.enums.UserStatus;
import com.github.rdsc.dev.ProSync.model.Role;
import com.github.rdsc.dev.ProSync.model.User;
import com.github.rdsc.dev.ProSync.model.UserRoleManager;
import com.github.rdsc.dev.ProSync.repository.RoleRepository;
import com.github.rdsc.dev.ProSync.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

@RequiredArgsConstructor
@Service
public class UserService { // Service：放「商業邏輯」（註冊流程），跟「資料存取（Repository）」分開
    private final UserRepository userRepo;
    private final RoleRepository roleRepo;
    private final UserRoleManager userRole;
    private final PasswordEncoder passwordEncoder;

//    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private static final Map<String, Integer> FAILS = new HashMap<>();
    private static final int MAX_FAILS = 3;

    // @Autowired // 只有 1 個建構子不用 @Autowired
//    public UserService(UserRepository userRepo, RoleRepository roleRepo, UserRoleManager userRole){
//        this.userRepo = userRepo;
//        this.roleRepo = roleRepo;
//        this.userRole = userRole;
//    }

    public User register(String email, String rawPassword){

        User u = new User();

        // 1/ 防止重複，先查 email
        if (userRepo.existsByEmail(email) && userRepo.findByEmail(email).get().getStatus() == UserStatus.PENDING_VERIFICATION){
            u = userRepo.findByEmail(email).orElseThrow();
        } else if(userRepo.existsByEmail(email) && userRepo.findByEmail(email).get().getStatus() != UserStatus.PENDING_VERIFICATION){
            throw new IllegalArgumentException("email already taken");
        } else if(!userRepo.existsByEmail(email)) {
            u = new User();
            u.setEmail(email);
        }

        // 2/ 雜湊密碼
        // String hashed = sha256Hex(rawPassword);
        String hashed = passwordEncoder.encode(rawPassword);

        // 3/ 組合 User 實體
        u.setPasswordHash(hashed);
        u.setStatus(UserStatus.PENDING_VERIFICATION);

        // 3.1/ 取出 USER 角色（沒有就丟錯，提醒先跑過 seed）
        Role uRole = roleRepo.findByUserRole(UserRole.USER)
                .orElseThrow(() -> new IllegalStateException("Cannot add a default role for this user, please check V3."));
        userRole.assign(u, uRole);

        // 4/ 寫入DB
        return userRepo.save(u);

    }

    public User authenticate(String email, String rawPassword){
        // 1/ 尋找使用者
        User u = userRepo.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("invalid credentials"));

        // 2/ 密碼比對
        boolean match = passwordMatches(rawPassword, u.getPasswordHash());

        String key = u.getEmail().toLowerCase(); // 當作計數用

        if (!match) {
            int now = FAILS.getOrDefault(key, 0) + 1;
            FAILS.put(key, now);

            // 到 3 次就暫停帳號
            if(now >=MAX_FAILS) {
                u.setStatus(com.github.rdsc.dev.ProSync.enums.UserStatus.SUSPENDED);
                u.setFailedLoginCount(0);
                userRepo.save(u);
                FAILS.remove(key); // 清掉計數
                throw new ResponseStatusException(HttpStatus.CONFLICT, "account suspended due to too many failed logins");
            }

            u.incrementFailedLogin();
            userRepo.save(u);

            // 未達 3 次，一律回同一句，避免洩漏資訊
            throw new IllegalArgumentException("invalid credentials");
        }

        FAILS.remove(key);

        if (u.getFailedLoginCount() > 0) {
            u.setFailedLoginCount(0);
            userRepo.save(u);
        }

        // 3/ 驗證通過，回傳 User（等等 Controller 會把它轉成 LoginResponse）
        return u;
        // 因為 Controller 後面還要用到使用者的資料（id、email、roles）來做回應/做 JWT，
        // 所以 authenticate(...) 成功時直接把「那個使用者」(u) 傳回來，Controller 才能立刻用，
        // 不用再查一次資料庫。
    }

    public Optional<User> findByEmail(String email){
        return userRepo.findByEmail(email);
    }

//    private String sha256Hex(String input){
//        try{
//            MessageDigest md = MessageDigest.getInstance("SHA-256");
//            byte[] out = md.digest(input.getBytes(StandardCharsets.UTF_8));
//            return HexFormat.of().formatHex(out);
//        } catch (Exception ex){
//            throw new IllegalStateException("HASH ERROR., ex");
//        }
//    }

    // 驗證密碼是否正確
    public boolean passwordMatches(String rawPassword, String storedHash){
        return passwordEncoder.matches(rawPassword, storedHash);
    }
}

