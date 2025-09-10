package com.github.rdsc.dev.ProSync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserLoginIntegrationTest {

    @Autowired
    MockMvc mockMvc; // 產出 MockMvc 物件

    @Autowired
    ObjectMapper objectMapper; // 把 Map 變 JSON 字串、或把回應 JSON 解析回 Java

    // 先註冊一個使用者（用時間戳當 email，避免重複）
    private String register(String email, String password) throws Exception {
        var body = Map.of("email", email, "password", password);
        mockMvc.perform(post("/api/users/register") // 打註冊 API
                .contentType(MediaType.APPLICATION_JSON) // 告訴對方：我送的是 JSON
                .content(objectMapper.writeValueAsString(body))) // 把 body 變成 JSON 字串放進去
                .andExpect(status().isCreated()); // 期望回 201
        return email; // 回傳 email
    }

    // 登入並取出 token
    private String loginAndGetToken(String email, String password) throws Exception {
        var body = Map.of("email", email, "password", password);
        var result = mockMvc.perform(post("/api/users/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.token").isNotEmpty()) // $.token：根底下的 token 欄位
                                                                                   // 用 JSONPath 去抓回應 JSON 裡的某個欄位
                            .andReturn(); // 讀裡面的字串
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        // 用 Jackson 拿到 .andReturn 模擬的 HTTP 回應物件，轉成JSON字串，解析成樹狀物件（JsonNode），存到變數 json
        // var resStr = result.getResponse().getContentAsString();
        // String token = JsonPath.read(resStr, "$.token");

        return json.get("token").asText(); // 把 token 字串取出
        // return json.path("token").asText();
    }

    @Test
    void login_success_then_call_me_with_token_should_200() throws Exception {
        String email = "u" + System.currentTimeMillis() + "@test.com";
        String pwd = "p@$$W0rd!";
        register(email, pwd);

        String token = loginAndGetToken(email, pwd);

        mockMvc.perform(get("/api/users/is-me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer" + token))
                .andExpect(status().isOk());
    }

    @Test
    void login_with_wrong_password_should_401()throws Exception {
        String email = "u" + System.nanoTime() + "@test.com";
        String pwd = "p@$$W0rd!";
        register(email, pwd);

        var body = Map.of("email", email, "password", "wrong-password");
        mockMvc.perform(post("/api/users/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized()); // 401
    }

    @Test // 沒帶 token 打 /is-me
    void call_me_without_token_should_401() throws Exception {
        mockMvc.perform(post("/api/users/is-me"))
                .andExpect(status().isUnauthorized()); // 401
    }
}
