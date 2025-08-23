package com.example.movie.service;

import com.example.movie.dto.MovieResponseDto;
import com.example.movie.repository.MovieRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;
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

    @Transactional(readOnly = true)
    public Optional<MovieResponseDto> getMovieById(String movieId) {
        try {
            // ★ 1. Convert the String movieId to a Long
            Long id = Long.parseLong(movieId);
            
            // ★ 2. Use the converted Long id to find the movie
            return movieRepository.findById(id)
                    .map(MovieResponseDto::new);
        } catch (NumberFormatException e) {
            // Handle cases where the movieId is not a valid number
            return Optional.empty();
        }
    }
}