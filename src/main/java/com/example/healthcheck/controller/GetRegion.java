package com.example.healthcheck.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

@RestController
@RequestMapping("/system")
@Tag(name = "System", description = "시스템 정보 API")
public class GetRegion {

    @Operation(summary = "현재 AWS 리전 조회(IMDSv2 전용)",
            description = "EC2/EKS/ECS에서 IMDSv2를 통해 현재 실행 리전을 반환합니다.")
    @GetMapping("/region")
    public ResponseEntity<Map<String, String>> getRegion() {
        String region = fetchRegionFromIMDSv2Only();
        return ResponseEntity.ok(Map.of(
                "region", region,
                "source", "imdsv2"
        ));
    }

    /** IMDSv2 토큰 발급 후 region만 조회. 실패 시 "unknown" */
    private String fetchRegionFromIMDSv2Only() {
        final String TOKEN_URL  = "http://169.254.169.254/latest/api/token";
        final String REGION_URL = "http://169.254.169.254/latest/meta-data/placement/region";
        final int TIMEOUT_MS = 800;

        try {
            // 1) IMDSv2 토큰 발급
            HttpURLConnection t = (HttpURLConnection) new URL(TOKEN_URL).openConnection();
            t.setRequestMethod("PUT");
            t.setConnectTimeout(TIMEOUT_MS);
            t.setReadTimeout(TIMEOUT_MS);
            t.setRequestProperty("X-aws-ec2-metadata-token-ttl-seconds", "21600");
            if (t.getResponseCode() != 200) return "unknown";
            String token;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(t.getInputStream()))) {
                token = br.readLine();
            } finally {
                t.disconnect();
            }
            if (token == null || token.isBlank()) return "unknown";

            // 2) 토큰으로 region 조회
            HttpURLConnection c = (HttpURLConnection) new URL(REGION_URL).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("X-aws-ec2-metadata-token", token);
            if (c.getResponseCode() != 200) return "unknown";
            try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                String region = br.readLine();
                return (region == null || region.isBlank()) ? "unknown" : region.trim();
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return "unknown";
        }
    }
}