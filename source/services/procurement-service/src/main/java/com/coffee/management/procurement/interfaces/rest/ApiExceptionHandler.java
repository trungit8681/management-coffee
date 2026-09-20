package com.coffee.management.procurement.interfaces.rest;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
@RestControllerAdvice
public class ApiExceptionHandler {
    public record Error(String code) {}
    @ExceptionHandler(SecurityException.class) ResponseEntity<Error> forbidden() { return ResponseEntity.status(403).body(new Error("FORBIDDEN")); }
    @ExceptionHandler({IllegalArgumentException.class,ArithmeticException.class,MethodArgumentNotValidException.class}) ResponseEntity<Error> invalid() { return ResponseEntity.badRequest().body(new Error("INVALID_REQUEST")); }
    @ExceptionHandler(IllegalStateException.class) ResponseEntity<Error> conflict(IllegalStateException ex) { return ResponseEntity.status(409).body(new Error(ex.getMessage())); }
}
