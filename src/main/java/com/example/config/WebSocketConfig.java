// ===============================================
// WebSocketConfig.java - 컴파일 오류 해결 버전
// ===============================================
package com.example.admission.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;  // ✅ 올바른 import
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

import java.util.Map;  // ✅ Map import 추가
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketConfig.class);
    
    // 🔥 연결 통계 추적
    private final AtomicLong activeConnections = new AtomicLong(0);
    private final AtomicLong totalConnections = new AtomicLong(0);
    private final ConcurrentHashMap<String, Long> sessionConnectTimes = new ConcurrentHashMap<>();

    /**
     * 🔥 메시지 브로커 설정 - 성능 최적화
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // 🔥 성능 최적화된 메모리 브로커 설정
        config.enableSimpleBroker("/topic")
            .setHeartbeatValue(new long[]{20000, 20000})  // 20초 heartbeat (부하 대응)
            .setTaskScheduler(taskScheduler())           // 커스텀 스케줄러 사용
            .setSelectorHeaderName("selector");          // 메시지 선택자 지원
            
        config.setApplicationDestinationPrefixes("/app");
        config.setPreservePublishOrder(true);  // 메시지 순서 보장
        config.setUserDestinationPrefix("/user"); // 개인 메시지 지원
        
        logger.info("✅ WebSocket 메시지 브로커 설정 완료 (Heartbeat: 20s)");
    }

    /**
     * 🔥 STOMP 엔드포인트 등록 - SockJS 최적화
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws-stomp")
            .setAllowedOriginPatterns("*")  // CORS 설정
            .withSockJS()
            // 🔥 SockJS 옵션 최적화 (부하 상황 대응)
            .setStreamBytesLimit(512 * 1024)      // 512KB 스트림 제한
            .setHttpMessageCacheSize(1000)        // HTTP 메시지 캐시 크기 증가
            .setDisconnectDelay(30 * 1000)        // 30초 연결 유지 (부하시 재연결 시간 확보)
            .setHeartbeatTime(20 * 1000)          // 20초 heartbeat
            .setSuppressCors(false);              // CORS 지원
            
        logger.info("✅ STOMP 엔드포인트 등록 완료 (/ws-stomp)");
    }

    /**
     * 🔥 WebSocket 전송 계층 최적화
     */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registry) {
        // 🔥 WebSocket 전송 계층 최적화 (부하 대응)
        registry
            .setMessageSizeLimit(256 * 1024)      // 256KB 메시지 크기 (증가)
            .setSendBufferSizeLimit(2 * 1024 * 1024)  // 2MB 송신 버퍼 (증가)
            .setSendTimeLimit(30000)              // 30초 전송 제한
            .setTimeToFirstMessage(60000);        // 첫 메시지 대기 시간 1분

        logger.info("✅ WebSocket 전송 계층 최적화 완료");
    }

    /**
     * 🔥 클라이언트 인바운드 채널 설정 (수신 메시지 처리)
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // 🔥 인바운드 채널 스레드 풀 최적화
        registration.taskExecutor()
            .corePoolSize(20)        // 코어 스레드 20개
            .maxPoolSize(100)        // 최대 스레드 100개 (부하 대응)
            .keepAliveSeconds(60)    // 60초 유지
            .queueCapacity(500);     // 큐 용량 500개
            
        // 🔥 메시지 인터셉터 추가 (로깅 및 모니터링)
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(
                    message, StompHeaderAccessor.class);
                    
                if (accessor != null) {
                    // 구독 요청 로깅
                    if (accessor.getCommand() != null) {
                        logger.debug("📨 인바운드 메시지: {} from {}", 
                                   accessor.getCommand(), 
                                   accessor.getSessionId());
                    }
                }
                
                return message;
            }
        });

        logger.info("✅ 클라이언트 인바운드 채널 설정 완료 (20-100 threads)");
    }

    /**
     * 🔥 클라이언트 아웃바운드 채널 설정 (송신 메시지 처리)
     */
    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        // 🔥 아웃바운드 채널 스레드 풀 최적화  
        registration.taskExecutor()
            .corePoolSize(20)        // 코어 스레드 20개
            .maxPoolSize(100)        // 최대 스레드 100개 (부하 대응)
            .keepAliveSeconds(60)    // 60초 유지
            .queueCapacity(500);     // 큐 용량 500개
            
        // 🔥 아웃바운드 메시지 인터셉터
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(
                    message, StompHeaderAccessor.class);
                    
                if (accessor != null && logger.isTraceEnabled()) {
                    logger.trace("📤 아웃바운드 메시지: destination={}", 
                               accessor.getDestination());
                }
                
                return message;
            }
        });

        logger.info("✅ 클라이언트 아웃바운드 채널 설정 완료 (20-100 threads)");
    }

    /**
     * 🔥 커스텀 태스크 스케줄러 - WebSocket용 최적화
     */
    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(30);                    // 스레드 풀 크기 (부하 대응)
        scheduler.setThreadNamePrefix("websocket-");  // 스레드 이름 접두사
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(60);
        scheduler.setRejectedExecutionHandler(
            new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy()
        );
        scheduler.initialize();
        
        logger.info("✅ WebSocket 태스크 스케줄러 생성 (30 threads)");
        return scheduler;
    }

    // ===============================================
    // 🔥 WebSocket 이벤트 리스너들 (모니터링 및 로깅)
    // ===============================================

    /**
     * WebSocket 연결 성공 이벤트
     */
    @EventListener
    public void handleWebSocketConnectListener(SessionConnectedEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = headerAccessor.getSessionId();
        
        // 연결 통계 업데이트
        long currentConnections = activeConnections.incrementAndGet();
        totalConnections.incrementAndGet();
        sessionConnectTimes.put(sessionId, System.currentTimeMillis());
        
        logger.info("🔗 WebSocket 연결됨: {} (활성연결: {})", sessionId, currentConnections);
        
        // 부하 상황 경고
        if (currentConnections > 1000) {
            logger.warn("⚠️ 높은 연결 수 감지: {}개 (부하 주의)", currentConnections);
        }
    }

    /**
     * WebSocket 연결 해제 이벤트
     */
    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = headerAccessor.getSessionId();
        
        // 연결 통계 업데이트
        long currentConnections = activeConnections.decrementAndGet();
        Long connectTime = sessionConnectTimes.remove(sessionId);
        
        // 연결 지속 시간 계산
        String durationInfo = "";
        if (connectTime != null) {
            long duration = System.currentTimeMillis() - connectTime;
            durationInfo = String.format(" (지속: %d초)", duration / 1000);
        }
        
        logger.info("🔌 WebSocket 연결 해제됨: {}{} (활성연결: {})", 
                  sessionId, durationInfo, currentConnections);
    }

    /**
     * 구독 이벤트
     */
    @EventListener
    public void handleSubscribeEvent(SessionSubscribeEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = headerAccessor.getSessionId();
        String destination = headerAccessor.getDestination();
        
        logger.debug("📋 구독 시작: {} -> {}", sessionId, destination);
        
        // 특정 토픽 구독 모니터링
        if (destination != null && destination.startsWith("/topic/admit/")) {
            String requestId = destination.substring("/topic/admit/".length());
            logger.info("🎫 입장 알림 구독: {} (requestId: {})", sessionId, requestId);
        }
    }

    /**
     * 구독 해제 이벤트
     */
    @EventListener
    public void handleUnsubscribeEvent(SessionUnsubscribeEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = headerAccessor.getSessionId();
        String subscriptionId = headerAccessor.getSubscriptionId();
        
        logger.debug("📋 구독 해제: {} (subscription: {})", sessionId, subscriptionId);
    }

    // ===============================================
    // 🔥 모니터링 및 관리 메서드들
    // ===============================================

    /**
     * 현재 WebSocket 연결 상태 조회
     */
    public Map<String, Object> getConnectionStats() {
        return Map.of(
            "activeConnections", activeConnections.get(),
            "totalConnections", totalConnections.get(),
            "averageSessionDuration", calculateAverageSessionDuration(),
            "timestamp", System.currentTimeMillis()
        );
    }

    /**
     * 평균 세션 지속 시간 계산
     */
    private long calculateAverageSessionDuration() {
        if (sessionConnectTimes.isEmpty()) {
            return 0;
        }
        
        long now = System.currentTimeMillis();
        return sessionConnectTimes.values().stream()
            .mapToLong(connectTime -> now - connectTime)
            .sum() / sessionConnectTimes.size();
    }

    /**
     * 연결 통계 초기화 (관리자용)
     */
    public void resetConnectionStats() {
        totalConnections.set(activeConnections.get());
        logger.info("🔄 WebSocket 연결 통계 초기화됨");
    }
}