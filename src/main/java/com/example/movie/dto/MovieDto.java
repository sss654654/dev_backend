package com.example.movie.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MovieDto {
    private String movieId;
    private String title;
    private String posterUrl;
    private String ageRating;
    private String genre;
    private int durationInMinutes;
    private List<String> showtimes;
}