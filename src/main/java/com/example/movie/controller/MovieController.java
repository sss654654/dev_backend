package com.example.movie.controller;

import com.example.admission.dto.EnterResponse; // EnterResponse DTO 임포트
import com.example.admission.service.AdmissionService;
import com.example.movie.dto.MovieResponseDto;
import com.example.movie.service.MovieService;
import com.example.session.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/movies")
public class MovieController {

    private final MovieService movieService;
    private final AdmissionService admissionService;
    private final SessionService sessionService;

    public MovieController(MovieService movieService, AdmissionService admissionService, SessionService sessionService) {
        this.movieService = movieService;
        this.admissionService = admissionService;
        this.sessionService = sessionService;
    }

    @GetMapping
    public ResponseEntity<List<MovieResponseDto>> getMovies() {
        return ResponseEntity.ok(movieService.getAllMovies());
    }

    @PostMapping("/{movieId}/enter")
    public ResponseEntity<Map<String, String>> tryEnterMovie(
            @PathVariable String movieId,
            HttpServletRequest request) {

        String sessionId = sessionService.requireValidSessionOrThrow(request);
        String requestId = UUID.randomUUID().toString();
        final String type = "movie";

        // 서비스의 반환 타입이 EnterResponse 객체로 변경됨
        EnterResponse result = admissionService.tryEnter(type, movieId, sessionId, requestId);

        // 결과 DTO의 status를 확인하여 분기 처리
        if (result.getStatus() == EnterResponse.Status.SUCCESS) {
            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "message", result.getMessage(),
                    "ticketingUrl", "/ticket/" + movieId + "?sessionId=" + sessionId
            ));
        } else { // QUEUED
            return ResponseEntity.accepted()
                    .header(HttpHeaders.LOCATION, result.getWaitUrl())
                    .body(Map.of(
                            "status", "QUEUED",
                            "message", result.getMessage(),
                            "waitUrl", result.getWaitUrl(),
                            "requestId", result.getRequestId()
                    ));
        }
    }
}