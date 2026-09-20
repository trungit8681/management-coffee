package com.coffee.management.identity.interfaces.rest;

import com.coffee.management.identity.application.IdentityException;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(IdentityException.class)
    ResponseEntity<Map<String, Object>> identity(IdentityException ex) {
        return ResponseEntity.status(ex.status())
                .body(Map.of("code", ex.code(), "message", ex.getMessage(), "timestamp", Instant.now().toString()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException ex) {
        return ResponseEntity.unprocessableEntity().body(Map.of("code", "VALIDATION_FAILED", "message",
                "Request validation failed", "timestamp", Instant.now().toString()));
    }
}
