package com.example.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.protocol.ProtocolVersion;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
@Profile({"dev", "staging", "prod"})
public class RedisConfig {
    
    private static final Logger logger = LoggerFactory.getLogger(RedisConfig.class);

    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private int redisPort;

    @Value("${spring.data.redis.ssl}")
    private boolean useSsl;

    @Bean(destroyMethod = "shutdown")
    public ClientResources clientResources() {
        return DefaultClientResources.create();
    }

    @Bean
    public LettuceConnectionFactory redisConnectionFactory(ClientResources clientResources) {
        logger.info("Configuring Redis connection to {}:{} (SSL: {})", redisHost, redisPort, useSsl);
        
        RedisStandaloneConfiguration redisConfig = 
            new RedisStandaloneConfiguration(redisHost, redisPort);
        
        SocketOptions socketOptions = SocketOptions.builder()
                .connectTimeout(Duration.ofSeconds(10))
                .keepAlive(true)
                .tcpNoDelay(true)
                .build();
        
        ClientOptions clientOptions = ClientOptions.builder()
                .socketOptions(socketOptions)
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .protocolVersion(ProtocolVersion.RESP3)
                .build();
        
        LettuceClientConfiguration.LettuceClientConfigurationBuilder builder = 
            LettuceClientConfiguration.builder()
                .clientResources(clientResources)
                .clientOptions(clientOptions)
                .commandTimeout(Duration.ofSeconds(2));
        
        if (useSsl) {
            builder.useSsl();
        }
        
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
            redisConfig, builder.build()
        );
        
        factory.setValidateConnection(true);
        logger.info("Redis connection factory created successfully");
        
        return factory;
    }

    @Bean
    public StringRedisTemplate redisTemplate(RedisConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(connectionFactory);
        
        StringRedisSerializer serializer = new StringRedisSerializer();
        template.setKeySerializer(serializer);
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(serializer);
        template.setHashValueSerializer(serializer);
        
        template.setEnableTransactionSupport(false);
        template.afterPropertiesSet();
        
        logger.info("Redis template configured successfully");
        return template;
    }
}