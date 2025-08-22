package com.example.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient;

import java.net.URI;
import java.time.Duration;

@Configuration
public class AwsConfig {
    
    /**
     * 운영환경용 Kinesis 클라이언트 (IRSA 사용)
     */
    @Bean
    @Profile("!local")
    public KinesisClient kinesisClient() {
        return KinesisClient.builder()
                .region(Region.AP_NORTHEAST_2)
                .credentialsProvider(DefaultCredentialsProvider.create()) // IRSA 지원
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                    .retryPolicy(RetryPolicy.builder()
                        .numRetries(3)
                        .build())
                    .apiCallTimeout(Duration.ofSeconds(30))
                    .apiCallAttemptTimeout(Duration.ofSeconds(10))
                    .build())
                .build();
    }
    
    /**
     * 운영환경용 비동기 Kinesis 클라이언트 (고성능 처리용)
     */
    @Bean
    @Profile("!local")
    public KinesisAsyncClient kinesisAsyncClient() {
        return KinesisAsyncClient.builder()
                .region(Region.AP_NORTHEAST_2)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .httpClient(NettyNioAsyncHttpClient.builder()
                    .maxConcurrency(50)
                    .maxPendingConnectionAcquires(1000)
                    .connectionTimeout(Duration.ofSeconds(30))
                    .connectionAcquisitionTimeout(Duration.ofSeconds(60))
                    .readTimeout(Duration.ofSeconds(30))
                    .writeTimeout(Duration.ofSeconds(30))
                    .build())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                    .retryPolicy(RetryPolicy.builder()
                        .numRetries(3)
                        .build())
                    .apiCallTimeout(Duration.ofSeconds(60))
                    .apiCallAttemptTimeout(Duration.ofSeconds(30))
                    .build())
                .build();
    }
    
    /**
     * 로컬 개발환경용 Kinesis 클라이언트 (LocalStack 사용)
     */
    @Bean
    @Profile("local")
    public KinesisClient localKinesisClient() {
        return KinesisClient.builder()
                .region(Region.AP_NORTHEAST_2)
                .endpointOverride(URI.create("http://localstack:4566"))
                .credentialsProvider(
                    StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))
                )
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                    .retryPolicy(RetryPolicy.builder()
                        .numRetries(2) // 로컬은 재시도 적게
                        .build())
                    .apiCallTimeout(Duration.ofSeconds(10))
                    .build())
                .build();
    }
    
    /**
     * 로컬 개발환경용 비동기 Kinesis 클라이언트
     */
    @Bean
    @Profile("local")
    public KinesisAsyncClient localKinesisAsyncClient() {
        return KinesisAsyncClient.builder()
                .region(Region.AP_NORTHEAST_2)
                .endpointOverride(URI.create("http://localstack:4566"))
                .credentialsProvider(
                    StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))
                )
                .build();
    }
}