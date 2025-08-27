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
                        // S3 버킷 URL (실제 버킷 이름)
                        "http://prod-iamroot-s3-fe.s3-website.ap-northeast-2.amazonaws.com",
                        "https://prod-iamroot-s3-fe.s3.ap-northeast-2.amazonaws.com",
                        // CloudFront 도메인
                        "https://cgv.peacemaker.kr",
                        "http://cgv.peacemaker.kr",
                        // API 도메인들 (dev 환경 추가!)
                        "https://api.peacemaker.kr",
                        "http://api.peacemaker.kr",
                        "https://dev.api.peacemaker.kr",  // ⭐ 추가된 부분
                        "http://dev.api.peacemaker.kr",   // ⭐ 추가된 부분
                        // 로컬 개발용
                        "http://localhost:3000",
                        "http://localhost:5173"
                )
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
                
        registry.addMapping("/system/**")
                .allowedOrigins(
                        // CloudFront 도메인
                        "https://cgv.peacemaker.kr",
                        "http://cgv.peacemaker.kr",
                        // API 도메인들 (dev 환경 추가!)
                        "https://api.peacemaker.kr",
                        "http://api.peacemaker.kr",
                        "https://dev.api.peacemaker.kr",  // ⭐ 추가된 부분
                        "http://dev.api.peacemaker.kr",   // ⭐ 추가된 부분
                        // 로컬 개발용
                        "http://localhost:3000",
                        "http://localhost:5173"
                )
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}