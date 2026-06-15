package com.jinjing.banking.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice // 告诉 Spring：我是全局“急诊室”
public class GlobalExceptionHandler {

    // 专门抓捕 @Valid 引起的校验失败错误 (Validation errors) 处理 JSR303 校验异常 (@Valid)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", HttpStatus.BAD_REQUEST.value());

        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error -> 
            errors.put(error.getField(), error.getDefaultMessage())
        );
        body.put("errors", errors);

        return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
    }

    // 专门抓捕数据库唯一性冲突 (e.g. Account number already exists)
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrityViolation(Exception ex) {
        log.error("Database conflict: {}", ex.getMessage());
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", HttpStatus.CONFLICT.value());
        body.put("message", "Data integrity violation: possibly duplicate account number.");

        return new ResponseEntity<>(body, HttpStatus.CONFLICT);
    }
    //  处理自定义业务异常 (BusinessException)
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusinessException(BusinessException ex) {
        log.error("Unhandled exception caught: ", ex);
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", ex.getStatus().value());
        body.put("message", ex.getMessage());
        return new ResponseEntity<>(body, ex.getStatus());
    }

    // 捕获其他未处理的异常，返回 500 错误
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAllExceptions(Exception ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        
        // [旧方案]：直接把 ex.getMessage() 返回。
        // 风险：会暴露数据库字段名、包名等敏感信息（Security Vulnerability）。
        // body.put("message", "An unexpected error occurred: " + ex.getMessage());
        
        // [新方案]：返回模糊的错误消息。
        // 面试谈资：符合安全开发的 OWASP 标准，防止攻击者通过错误信息探测系统结构。
        body.put("message", "A system error occurred. Please contact support.");
        log.error("CRITICAL ERROR: ", ex); // 细节留在服务器日志里，不发给用户

        return new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}