package com.example.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kinesis.KinesisClient;

@Configuration
public class AwsConfig {

    @Bean
    public KinesisClient kinesisClient() {
        // AWS SDK가 자동으로 자격 증명(credentials)을 찾도록 설정합니다.
        // (컨테이너 실행 시 ~/.aws/credentials를 연결했기 때문에 가능)
        return KinesisClient.builder()
                .region(Region.AP_NORTHEAST_2) // 서울 리전
                .build();
    }
}