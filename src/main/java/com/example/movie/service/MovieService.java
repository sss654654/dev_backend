package com.example.movie.service;

import com.example.movie.dto.MovieResponseDto;
import com.example.movie.repository.MovieRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional; // ★ Optional 임포트 추가
import java.util.stream.Collectors;

@Service
public class MovieService {

    private final MovieRepository movieRepository;

    public MovieService(MovieRepository movieRepository) {
        this.movieRepository = movieRepository;
    }

    @Transactional(readOnly = true)
    public List<MovieResponseDto> getAllMovies() {
        return movieRepository.findAll().stream()
                .map(MovieResponseDto::new)
                .collect(Collectors.toList());
    }

    // ★ 누락되었던 getMovieById 메서드 추가
    @Transactional(readOnly = true)
    public Optional<MovieResponseDto> getMovieById(String movieId) {
        return movieRepository.findById(movieId)
                .map(MovieResponseDto::new); // 조회된 Movie 엔티티를 DTO로 변환
    }
}