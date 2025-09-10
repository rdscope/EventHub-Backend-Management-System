package com.github.rdsc.dev.ProSync.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins("http://localhost:5173")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");

        registry.addMapping("/actuator/**") // Actuator 是由 Spring Boot Actuator 模組自己註冊的 ServletEndpointHandlerMapping、WebEndpointHandlerMapping 管理，不走 MVC Handler
                .allowedOrigins("http://localhost:5173", "http://127.0.0.1:5173")
                .allowedMethods("GET", "OPTIONS")
                .allowedHeaders("*");
    }
}
//@Configuration
//public class WebConfig {
//
//    @Bean
//    public WebMvcConfigurer corsConfigurer() {
//        return new WebMvcConfigurer() {
//
//            @Override
//            public void addCorsMappings(CorsRegistry registry) {
//                registry.addMapping("/**") // WebMvcConfigurer 的 DispatcherServlet 處理的路徑（一般的 @RestController）
//                        // 允許前端來源（Vite 開發伺服器）
//                        .allowedOrigins("http://localhost:5173", "http://127.0.0.1:5173")
//                        // HTTP 方法
//                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
//                        // 允許帶哪些自訂標頭（Authorization）
//                        .allowedHeaders("*")
//                        // 若未使用 Cookie，可不必 allowCredentials
//                        .allowCredentials(true);
//
//                registry.addMapping("/actuator/**") // Actuator 是由 Spring Boot Actuator 模組自己註冊的 ServletEndpointHandlerMapping、WebEndpointHandlerMapping 管理，不走 MVC Handler
//                        .allowedOrigins("http://localhost:5173", "http://127.0.0.1:5173")
//                        .allowedMethods("GET", "OPTIONS")
//                        .allowedHeaders("*");
//            }
//        };
//    }
//}
