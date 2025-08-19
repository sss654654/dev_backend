package com.example.movie.dto;

import com.example.movie.entity.Movie;
import com.example.movie.entity.Showtime;
import java.util.List;
import java.util.stream.Collectors;

public class MovieResponseDto {
    private String movieId;
    private String title;
    private String description;
    private String posterUrl;
    private String ageRating;
    private String genre;
    private int durationInMinutes;
    private List<String> showtimes;

    public MovieResponseDto(Movie movie) {
        this.movieId = movie.getMovieId();
        this.title = movie.getTitle();
        this.description = movie.getDescription();
        this.posterUrl = movie.getPosterUrl();
        this.ageRating = movie.getAgeRating();
        this.genre = movie.getGenre();
        this.durationInMinutes = movie.getDurationInMinutes();
        this.showtimes = movie.getShowtimes().stream()
                               .map(Showtime::getShowTime)
                               .collect(Collectors.toList());
    }

    // Getters
    public String getMovieId() {
        return movieId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getPosterUrl() {
        return posterUrl;
    }

    public String getAgeRating() {
        return ageRating;
    }

    public String getGenre() {
        return genre;
    }

    public int getDurationInMinutes() {
        return durationInMinutes;
    }

    public List<String> getShowtimes() {
        return showtimes;
    }
}