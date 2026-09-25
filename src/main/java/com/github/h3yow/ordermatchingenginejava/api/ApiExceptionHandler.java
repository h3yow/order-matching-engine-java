package com.github.h3yow.ordermatchingenginejava.api;

import com.github.h3yow.ordermatchingenginejava.domain.TradingException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 业务拒绝 -> HTTP 422 + 可区分 reason；参数错误 -> HTTP 400。 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(TradingException.class)
    public ResponseEntity<Map<String, String>> handleTrading(TradingException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("rejectReason", ex.getReason().name(), "message", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArg(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }
}
