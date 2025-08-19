package com.example.couponmanagement.ws;


import com.example.couponmanagement.dto.WaitUpdateMessage;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class LiveUpdatePublisher {
    private final SimpMessagingTemplate template;
    public LiveUpdatePublisher(SimpMessagingTemplate template) { this.template = template; }

    public void publishWait(String requestId, int position, String status) {
        String dest = "/topic/wait/" + requestId;
        template.convertAndSend(dest,
                new WaitUpdateMessage(requestId, position, status, Instant.now().toEpochMilli()));
    }
}