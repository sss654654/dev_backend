package com.example.admission.controller;

import com.example.admission.KinesisAdmissionConsumer;
import com.example.admission.KinesisAdmissionProducer;
import com.example.admission.service.AdmissionMetricsService;
import com.example.admission.service.AdmissionService;
import com.example.admission.ws.WebSocketUpdateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/debug")
@Tag(name = "Debug", description = "시스템 내부 상태 확인용 디버그 API")
public class DebugController {

    private final AdmissionService admissionService;
    private final AdmissionMetricsService metricsService;
    private final KinesisAdmissionProducer kinesisProducer;
    private final KinesisAdmissionConsumer kinesisConsumer;
    private final WebSocketUpdateService webSocketUpdateService;

    public DebugController(AdmissionService admissionService,
                           AdmissionMetricsService metricsService,
                           KinesisAdmissionProducer kinesisProducer,
                           KinesisAdmissionConsumer kinesisConsumer,
                           WebSocketUpdateService webSocketUpdateService) {
        this.admissionService = admissionService;
        this.metricsService = metricsService;
        this.kinesisProducer = kinesisProducer;
        this.kinesisConsumer = kinesisConsumer;
        this.webSocketUpdateService = webSocketUpdateService;
    }

    @Operation(summary = "전체 시스템 상태 종합 조회", description = "주요 컴포넌트들의 상태와 통계를 한 번에 확인합니다.")
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getFullSystemStatus() {
        Map<String, Object> status = new HashMap<>();
        
        try {
            status.put("systemSummary", metricsService.getSystemSummary());
            status.put("webSocketStats", webSocketUpdateService.getWebSocketStats());

            // ✨✨✨ 핵심 수정 ✨✨✨
            // 컴파일 오류를 유발하는 Kinesis 관련 상태 조회 로직을 임시로 비활성화합니다.
            // 이 부분은 시스템의 핵심 기능에 영향을 주지 않습니다.
            // status.put("kinesisConsumerStats", kinesisConsumer.getConsumerStats());
            // status.put("kinesisProducerHealthy", kinesisProducer.isKinesisHealthy());
            
            // 임시로 Kinesis 상태를 "CHECK_DISABLED"로 표시합니다.
            status.put("kinesisStatus", "CHECK_DISABLED");
            
        } catch (Exception e) {
            status.put("error", "상태 조회 중 오류 발생: " + e.getMessage());
        }
        
        return ResponseEntity.ok(status);
    }
}