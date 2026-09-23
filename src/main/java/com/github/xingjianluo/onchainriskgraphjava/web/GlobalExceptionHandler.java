package com.github.xingjianluo.onchainriskgraphjava.web;

import com.github.xingjianluo.onchainriskgraphjava.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一错误响应：code + message + httpStatus 稳定可区分。
 * 重点区分：
 * - DUPLICATE 不是错误（HTTP 200, status=DUPLICATE）；
 * - CONFLICT（同 id 不同内容）= 409；
 * - INVALID_RULE（非法/冲突规则）= 422；
 * - INVALID_INPUT = 400。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handle(ApiException ex) {
        log.warn("[接口异常] code={} status={} message={}", ex.getCode(), ex.httpStatus(), ex.getMessage());
        return ResponseEntity.status(ex.httpStatus()).body(body(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(body("INVALID_INPUT", "请求体无法解析: " + ex.getMostSpecificCause().getMessage()));
    }

    private static Map<String, Object> body(String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("message", message);
        m.put("timestamp", Instant.now().toEpochMilli());
        return m;
    }
}
