// src/main/java/com/example/admission/exception/GlobalExceptionHandler.java
package com.example.admission.exception;

import org.slf4j.Logger; import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus; import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice; import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String,Object>> handle(Exception e){
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Map.of("status","ERROR","message","서버 내부 오류"));
    }
}
