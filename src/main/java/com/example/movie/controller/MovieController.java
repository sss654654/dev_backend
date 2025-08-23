package com.example.movie.controller;

import com.example.movie.dto.MovieResponseDto; // ★ MovieDto -> MovieResponseDto로 변경
import com.example.movie.service.MovieService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/movies")
public class MovieController {

    private final MovieService movieService;

    public MovieController(MovieService movieService) {
        this.movieService = movieService;
    }

    @GetMapping
    // ★ 반환 타입을 List<MovieResponseDto>로 수정
    public ResponseEntity<List<MovieResponseDto>> getAllMovies() {
        return ResponseEntity.ok(movieService.getAllMovies());
    }

    @GetMapping("/{movieId}")
    // ★ 반환 타입을 MovieResponseDto로 수정
    public ResponseEntity<MovieResponseDto> getMovieById(@PathVariable String movieId) {
        return movieService.getMovieById(movieId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}