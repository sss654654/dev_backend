package com.example.session.controller;

import com.example.session.service.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@RestController
@Tag(name = "Session Management", description = "세션 관리 API")
public class SessionController {
    private final SessionService sessionService;
    private static final String COOKIE_NAME = "SID";

    SessionController(SessionService sessionService){
        this.sessionService = sessionService;
    }
    @Operation(summary = "세션 ID 발급", description = "사용자 구분을 위한 고유 세션 ID를 발급합니다.")
    @ApiResponse(responseCode = "200", description = "성공",
            content = @Content(schema = @Schema(type = "object", example = "{\"sessionId\": \"a1b2c3d4-e5f6-7890-1234-567890abcdef\"}")))
    @GetMapping("/api/session/issue")
    public ResponseEntity<Void> issueSessionId(HttpServletRequest request) {
        String sessionId = UUID.randomUUID().toString();
        sessionService.createSession(sessionId);


        boolean isHttps = request.isSecure(); // 배포환경이라면 대부분 true
        // 개발 중 (http) → Lax/secure=false, 운영(https, 크로스사이트) → None/secure=true 권장
        String sameSite = isHttps ? "None" : "Lax";
        boolean secure   = isHttps; // 운영 HTTPS에서 true 로 동작

        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, sessionId)
                .httpOnly(true)
                .secure(secure)
                .path("/")
                .maxAge(Duration.ofHours(1))
                .sameSite(sameSite)
                .build();

        return ResponseEntity
                .noContent()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .build();
    }
}