package com.example.queue.service;

import jakarta.annotation.PostConstruct;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

@Service
public class QueueService {

    private static final String WAITING_QUEUE_KEY = "waiting_queue";
    private final RedisTemplate<String, String> redisTemplate;
    private ZSetOperations<String, String> zSetOperations;

    public QueueService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void init() {
        this.zSetOperations = redisTemplate.opsForZSet();
    }

    public Boolean addUserToQueue(String sessionId) {
        return zSetOperations.add(WAITING_QUEUE_KEY, sessionId, System.currentTimeMillis());
    }

    public Long getUserRank(String sessionId) {
        return zSetOperations.rank(WAITING_QUEUE_KEY, sessionId);
    }

    public Long getTotalWaitingCount() {
        return zSetOperations.size(WAITING_QUEUE_KEY);
    }
}