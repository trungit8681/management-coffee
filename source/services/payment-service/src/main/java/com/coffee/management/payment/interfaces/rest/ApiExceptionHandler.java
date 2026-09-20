package com.coffee.management.payment.interfaces.rest;

import com.coffee.management.payment.application.PaymentException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiExceptionHandler {
    public record Error(String code, String message) {}
    @ExceptionHandler(PaymentException.class)
    ResponseEntity<Error> payment(PaymentException ex) {
        return ResponseEntity.status(ex.status()).body(new Error(ex.code(), ex.getMessage()));
    }
    @ExceptionHandler(SecurityException.class)
    ResponseEntity<Error> forbidden(SecurityException ex) {
        return ResponseEntity.status(403).body(new Error("FORBIDDEN", "Permission or branch scope denied"));
    }
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    ResponseEntity<Error> invalid(Exception ex) {
        return ResponseEntity.badRequest().body(new Error("INVALID_REQUEST", "Invalid payment request"));
    }
}
