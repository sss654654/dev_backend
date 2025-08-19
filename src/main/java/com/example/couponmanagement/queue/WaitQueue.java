package com.example.couponmanagement.queue;// waiting/HardcodedWaitQueue.java


import com.example.couponmanagement.ws.LiveUpdatePublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class WaitQueue {

    private final LiveUpdatePublisher publisher;
    private final Map<String, AtomicInteger> positions = new ConcurrentHashMap<>();

    public WaitQueue(LiveUpdatePublisher publisher) { this.publisher = publisher; }

    /** 사용자 등록 → requestId 발급 + 초기 상태 알림 */
    public String enqueue(String userId) {
        String requestId = UUID.randomUUID().toString();
        int base = positions.size();
        int fudge = Math.max(3, (int)(Math.random() * 7));     // 대충 3~9초 사이 대기
        positions.put(requestId, new AtomicInteger(base + fudge));
        publisher.publishWait(requestId, positions.get(requestId).get(), "PENDING");
        return requestId;
    }

    /** 1초마다 전체 업데이트 */
    @Scheduled(fixedDelay = 1000)
    public void tick() {
        positions.forEach((reqId, counter) -> {
            int now = Math.max(0, counter.decrementAndGet());
            if (now == 0) {
                publisher.publishWait(reqId, 0, "COMPLETE");
                positions.remove(reqId);
            } else {
                publisher.publishWait(reqId, now, "PENDING");
            }
        });
    }
}
