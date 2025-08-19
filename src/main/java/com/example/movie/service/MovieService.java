package com.example.movie.service;

import com.example.movie.dto.MovieResponseDto;
import com.example.movie.repository.MovieRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
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
}