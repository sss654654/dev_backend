package com.example.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(
                        // CloudFront 도메인
                        "https://cgv.peacemaker.kr",
                        "http://cgv.peacemaker.kr",
                        // 로컬 개발용
                        "http://localhost:3000",
                        "http://localhost:5173"
                ) // ★ 이 라인 끝에 세미콜론(;)이 없어야 합니다.
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true); // ★ 세미콜론은 전체 문장의 맨 마지막에 한 번만 와야 합니다.
        registry.addMapping("/system/**")
                .allowedOrigins(
                        // CloudFront 도메인
                        "https://cgv.peacemaker.kr",
                        "http://cgv.peacemaker.kr",
                        // 로컬 개발용
                        "http://localhost:3000",
                        "http://localhost:5173"
                ) // ★ 이 라인 끝에 세미콜론(;)이 없어야 합니다.
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true); // ★ 세미콜론은 전체 문장의 맨 마지막에 한 번만 와야 합니다.
    }
}