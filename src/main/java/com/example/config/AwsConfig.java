package com.example.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kinesis.KinesisClient;

import java.net.URI;

@Configuration
public class AwsConfig {

    @Bean
    @Profile("!local")
    public KinesisClient kinesisClient() {
        return KinesisClient.builder()
                .region(Region.AP_NORTHEAST_2)
                .build();
    }

    @Bean
    @Profile("local")
    public KinesisClient localKinesisClient() {
        return KinesisClient.builder()
                .region(Region.AP_NORTHEAST_2)
                .endpointOverride(URI.create("http://localstack:4566"))
                // [수정] 아래 두 줄을 AnonymousCredentialsProvider 대신 사용합니다.
                .credentialsProvider(
                    StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))
                )
                .build();
    }
}