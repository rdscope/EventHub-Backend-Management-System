package com.github.rdsc.dev.ProSync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.rdsc.dev.ProSync.enums.UserRole;
import com.github.rdsc.dev.ProSync.model.Role;
import com.github.rdsc.dev.ProSync.model.User;
import com.github.rdsc.dev.ProSync.repository.RoleRepository;
import com.github.rdsc.dev.ProSync.repository.UserRepository;
import com.github.rdsc.dev.ProSync.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminRbacIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserService userService;
    @Autowired UserRepository userRepo;
    @Autowired RoleRepository roleRepo;

    // 註冊
    private String register(String email, String password) throws Exception {
        var body = Map.of("email", email, "password", password);
        var res = mockMvc.perform(post("/api/users/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk())
                    .andReturn();

        JsonNode json = objectMapper.readTree(res.getResponse().getContentAsString());
        return json.has("verificationCode") ? json.get("verificationCode").asText() : null;
    }

    // 用 email + code 完成驗證
    private void verify(String email, String code) throws Exception {
        var body = Map.of("email", email, "code", code);
        mockMvc.perform(post("/api/users/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is2xxSuccessful());
    }

    // 登入拿 token
    private String loginAndGetToken(String email, String password) throws Exception {
        var body = Map.of("email", email, "password", password);
        var res = mockMvc.perform(post("/api/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(res.getResponse().getContentAsString());
        return json.get("token").asText();
    }

    // 把某使用者升級成 ADMIN（注意：要在「登入前」給角色，token 才會帶到）
    private void grantAdmin(String email, String role) {
        User u = userService.findByEmail(email).orElseThrow();
        UserRole uR = UserRole.valueOf(role);
        Role admin = roleRepo.findByUserRole(uR).orElseGet(() -> roleRepo.save(Role.builder().userRole(UserRole.ADMIN).build()));
        u.addRole(admin);
        userRepo.save(u);
    }

    @Test
    @Transactional
    void admin_ping_rbac_flow() throws Exception {
        // 1/ 沒帶 token → 401
        mockMvc.perform(get("/api/admin/ping"))
                .andExpect(status().isUnauthorized());

        // 2/ 一般 USER → 403
        String userEmail = "u" + System.currentTimeMillis() + "@test.com";
        String pwd = "P@ssw0rd!";
        String userCode = register(userEmail, pwd);
        verify(userEmail, userCode);
        String userToken = loginAndGetToken(userEmail, pwd);

        mockMvc.perform(get("/api/admin/ping")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden());

        // 3/ ADMIN → 200
        String adminEmail = "a" + System.nanoTime() + "@test.com";

        String adminCode = register(adminEmail, pwd);  // 1/ 註冊
        verify(adminEmail, adminCode);                 // 2/ 完成驗證（很關鍵：否則無法登入）
        grantAdmin(adminEmail,"ADMIN");                        // 3/ 登入前給 ADMIN 角色
        String adminToken = loginAndGetToken(adminEmail, pwd); // 4/ 登入拿到帶 ADMIN 的 token

        mockMvc.perform(get("/api/admin/ping")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
    }
}
