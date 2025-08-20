package com.example.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.WebIdentityTokenFileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kinesis.KinesisClient;

import java.net.URI;

@Configuration
public class AwsConfig {
    
    private static final Logger logger = LoggerFactory.getLogger(AwsConfig.class);

    @Value("${aws.region}")
    private String awsRegion;

    @Value("${aws.kinesis.endpoint:}")
    private String kinesisEndpoint;

    @Value("${aws.kinesis.use-vpc-endpoint:false}")
    private boolean useVpcEndpoint;

    @Value("${aws.credentials.use-irsa:false}")
    private boolean useIrsa;

    @Bean
    @Profile({"dev", "staging", "prod"})
    public KinesisClient kinesisClient() {
        logger.info("Initializing Kinesis Client for AWS environment");
        
        var builder = KinesisClient.builder()
                .region(Region.of(awsRegion));

        // VPC Endpoint 사용
        if (useVpcEndpoint && !kinesisEndpoint.isEmpty()) {
            logger.info("Using Kinesis VPC Endpoint: {}", kinesisEndpoint);
            builder.endpointOverride(URI.create(kinesisEndpoint));
        }

        // IRSA 사용 (EKS ServiceAccount)
        if (useIrsa) {
            logger.info("Using IRSA for AWS credentials");
            // EKS에서는 자동으로 WebIdentityTokenFileCredentialsProvider 사용
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        } else {
            logger.info("Using default AWS credentials chain");
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }

        KinesisClient client = builder.build();
        logger.info("Kinesis Client initialized successfully");
        return client;
    }
}