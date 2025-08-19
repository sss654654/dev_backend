package com.example.session.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@Tag(name = "Session Management", description = "세션 관리 API")
public class SessionController {

    @Operation(summary = "세션 ID 발급", description = "사용자 구분을 위한 고유 세션 ID를 발급합니다.")
    @ApiResponse(responseCode = "200", description = "성공",
            content = @Content(schema = @Schema(type = "object", example = "{\"sessionId\": \"a1b2c3d4-e5f6-7890-1234-567890abcdef\"}")))
    @GetMapping("/api/session/issue")
    public ResponseEntity<Map<String, String>> issueSessionId() {
        String sessionId = UUID.randomUUID().toString();
        Map<String, String> response = Map.of("sessionId", sessionId);
        return ResponseEntity.ok(response);
    }
}