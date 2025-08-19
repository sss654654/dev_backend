-- src/main/resources/data.sql

-- 기존 users 테이블 INSERT 문 아래에 추가 --

-- 영화 테이블 생성 (JPA ddl-auto가 해주지만, 명시적으로 작성)
DROP TABLE IF EXISTS showtimes;
DROP TABLE IF EXISTS movies;

CREATE TABLE movies (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    movie_id VARCHAR(255) NOT NULL UNIQUE,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    poster_url VARCHAR(255),
    age_rating VARCHAR(50),
    genre VARCHAR(100),
    duration_in_minutes INT
);

CREATE TABLE showtimes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    movie_id BIGINT,
    show_time VARCHAR(5),
    FOREIGN KEY (movie_id) REFERENCES movies(id)
);

-- 샘플 영화 데이터 삽입
INSERT INTO movies (movie_id, title, description, poster_url, age_rating, genre, duration_in_minutes) VALUES
('movie-avatar3', '아바타 3', '판도라의 새로운 이야기가 시작됩니다.', '/posters/avatar3.jpg', '12세 이상', 'SF/액션', 180),
('movie-spiderman2', '스파이더맨: 뉴 유니버스 2', '멀티버스를 넘나드는 스파이더맨들의 활약', '/posters/spiderman2.jpg', '12세 이상', '애니메이션', 130),
('movie-topgun2', '탑건: 매버릭 2', '전설의 파일럿, 그의 새로운 미션', '/posters/topgun2.jpg', '12세 이상', '액션', 130),
('movie-interstellar', '인터스텔라 리마스터', '우리는 답을 찾을 것이다, 늘 그랬듯이.', '/posters/interstellar.jpg', '12세 이상', 'SF/드라마', 169);

-- 샘플 상영 시간 데이터 삽입
-- 아바타 3 (movie.id = 1)
INSERT INTO showtimes (movie_id, show_time) VALUES (1, '10:00'), (1, '13:30'), (1, '17:00'), (1, '20:30');
-- 스파이더맨 2 (movie.id = 2)
INSERT INTO showtimes (movie_id, show_time) VALUES (2, '09:30'), (2, '12:00'), (2, '14:30'), (2, '19:00'), (2, '21:30');
-- 탑건 2 (movie.id = 3)
INSERT INTO showtimes (movie_id, show_time) VALUES (3, '11:00'), (3, '14:00'), (3, '17:30'), (3, '20:00');
-- 인터스텔라 (movie.id = 4)
INSERT INTO showtimes (movie_id, show_time) VALUES (4, '12:00'), (4, '18:00');