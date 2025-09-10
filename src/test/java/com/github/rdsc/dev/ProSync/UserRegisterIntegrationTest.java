package com.github.rdsc.dev.ProSync;

import org.junit.jupiter.api.Test;
// 從 JUnit 引入 @Test 註解。用它標記「這是一個測試方法」。
import org.springframework.beans.factory.annotation.Autowired;
// 引入 @Autowired，請 Spring 自動把物件塞進來（自動注入）。
import org.springframework.boot.test.autoconfigure.core.AutoConfigureCache;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
// → 引入 @AutoConfigureMockMvc，幫忙把 Web 測試環境（MockMvc）準備好。
import org.springframework.boot.test.context.SpringBootTest;
// 引入 @SpringBootTest，用來整合測試：會啟動整個 Spring Boot。
import org.springframework.boot.test.web.client.TestRestTemplate;
// → 引入 TestRestTemplate，一個在測試裡發 HTTP 請求的工具。
import org.springframework.http.HttpStatus;
// 引入 HttpStatus，用 HttpStatus.CREATED（201）來比對回傳狀態碼。
import org.springframework.http.ResponseEntity;
// 引入 ResponseEntity，代表「HTTP 回應（含狀態碼、body）」。

import java.util.*;
// 引入 java.util 底下常用的集合類別（List、Map、UUID 等）。

import static org.junit.jupiter.api.Assertions.*;
// 把斷言方法（assertEquals、assertTrue…）用靜態匯入，寫起來比較短。

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// 告訴 Spring：用隨機埠號啟動整個應用程式來跑測試。這樣能真的打到 POST /api/users/register。
@AutoConfigureMockMvc
// 啟用 Web 測試相關自動設定（讓 MockMvc 等元件可以被注入）。雖然這支測試主力是 TestRestTemplate，但加了不會壞。
// 這支測試沒用到 MockMvc
class UserRegisterIntegrationTest {

    @Autowired
    private TestRestTemplate rest;
    // 宣告一個 TestRestTemplate 物件，用它送 HTTP 請求啟動的應用程式。

    @Test // JUnit 會執行它
    void register_shouldReturnUSERRole(){
        String email = "u" + UUID.randomUUID().toString().substring(0,8) + "@test.com";
        // UUID 取前 8 碼，接在 "u" 後面

        Map<String, String> req = new LinkedHashMap<>();
        req.put("email", email);
        req.put("password", "p@$$w0rd!");

        // Registering Path Testing: POST /api/users/register
        ResponseEntity<Map> resp = rest.postForEntity("/api/users/register", req, Map.class);
        // 真的送出 POST 請求到 "/api/users/register"；把 req 當 JSON body  (要快速組一個 JSON 當請求 body，Map 最方便)
        // 預期回來的 body 先用通用的 Map 接（方便檢查欄位）

        System.out.println(">>> HTTP STATUS = " + resp.getStatusCodeValue() + " " + resp.getStatusCode());
        System.out.println(">>> BODY = " + resp.getBody());

        System.out.println("=== Register API response ===");
        System.out.println("Status: " + resp.getStatusCodeValue());
        System.out.println("Body  : " + resp.getBody());
        System.out.println("Role  : " + resp.getBody().get("roles"));

        assertEquals(HttpStatus.CREATED, resp.getStatusCode(), "supposed to be 201 CREATED. " +
                                       "Actual=\" + resp.getStatusCode() + \", body=\" + resp.getBody()");
        // System.out.println(...)：永遠印出來（成功也看得到）。
        // assertEquals(..., "訊息")：只有失敗才會把「訊息」印出來，方便定位問題。

        Map body = resp.getBody(); // 把回傳的 body 取出來，存到 body。
        assertNotNull(body, "Shouldn't return NULL");

        assertEquals(email, body.get("email"), "Returned email address should be the same as user requseted");

        Object rolesObj = body.get("roles");
        assertNotNull(rolesObj, "Should appear the ROLE column");

        // Change to LIST<> to check USER role
//        @SuppressWarnings("unchecked") // 告訴編譯器：不要對下面這行轉型碎碎念（因為泛型在執行期擦除，我知道自己在做什麼）
//        List<String> roles = (List<String>) rolesObj; // 把 rolesObj 轉成 List<String>。預期是像 ["USER"] 這樣的內容
//        assertTrue(roles.contains("USER"), "新註冊預期要有角色 USER");
//        壞處
//        若不是 List<String>（例如 null、List<?>、List<Map>…）：
//        可能丟 ClassCastException（還沒斷言就炸了）。
//        或 roles 變 null，roles.contains(...) 會 NPE。
//        對「型別一點點變動」很脆弱（例如改回 Set<String>、或序列化器行為不同）。
        List<?> raw = (rolesObj instanceof List) ? (List<?>) rolesObj : Collections.emptyList();
        // 判斷 rolesObj 這個物件「是不是 List」 (instanceof：是否是某型別的判斷)，
        //      是則 把 rolesObj「轉型」成「元素型別不確定的 List」。轉型是因為上面判斷它確實是 List 了，就安全把它當作 List 看待，給raw
        //      否則 回傳raw一個「空的 List」 (Collections：集合工具類別)，而且是不可修改的（不能 add）(回傳空清單，而不是 null，之後迴圈就不會噴 NullPointerException, NPE)。
        List<String> roles = new ArrayList<>();
        for(Object o : raw) roles.add(String.valueOf(o));
        // 增強型 for 迴圈，每次拿到的元素，先用最通用的型別 Object 接住（因為 raw 的元素型別不確定）
        // 從 raw 這個清單逐一取出元素
        // 把 o 轉成字串，「加到」roles // (String.valueOf(o))
        // 把 raw 裡的每個元素，都「轉成字串」，然後加入 roles。
        // 最終 roles 會是 List<String>

        assertTrue(roles.contains("USER"), "New member are expected to have a User role");
    }
}
