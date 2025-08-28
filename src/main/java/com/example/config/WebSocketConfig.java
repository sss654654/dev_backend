// ========================================
// 1. WebSocket 설정 개선 (WebSocketConfig.java)
// ========================================

package com.example.admission.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws-stomp")
                .setAllowedOriginPatterns("*")
                .withSockJS()
                .setHeartbeatTime(25000); // ✅ SockJS 하트비트 25초로 설정
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic")
                .setHeartbeatValue(new long[]{25000, 25000}); // ✅ 서버 하트비트 25초
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration
                .setMessageSizeLimit(64 * 1024)     // 64KB 메시지 크기
                .setSendBufferSizeLimit(512 * 1024) // 512KB 송신 버퍼
                .setSendTimeLimit(20 * 1000);       // 20초 전송 타임아웃
    }
}
