package com.example.session.controller;

import com.example.session.service.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

@RestController
@RequestMapping("/api/sessions")
@Tag(name = "Session Management", description = "세션 관리 API")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Operation(summary = "세션 ID 발급", description = "사용자 구분을 위한 고유 세션 ID를 발급하고 쿠키에 설정합니다.")
    @ApiResponse(responseCode = "204", description = "성공. 응답 헤더의 Set-Cookie를 확인하세요.")
    @GetMapping("/issue")
    public ResponseEntity<Void> issueSession(HttpServletRequest request) {
        String sessionId = UUID.randomUUID().toString();
        sessionService.createSession(sessionId);

        boolean isSecure = request.isSecure(); // HTTPS 여부
        String sameSite = isSecure ? "None" : "Lax";

        ResponseCookie cookie = ResponseCookie.from(SessionService.COOKIE_NAME, sessionId)
                .httpOnly(true)
                .secure(isSecure)
                .path("/")
                .maxAge(Duration.ofHours(1))
                .sameSite(sameSite)
                .build();

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .build();
    }
}