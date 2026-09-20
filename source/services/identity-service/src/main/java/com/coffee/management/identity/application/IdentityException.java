package com.coffee.management.identity.application;

public class IdentityException extends RuntimeException {
    private final String code;
    private final int status;

    public IdentityException(String code, String message, int status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }

    public static IdentityException unauthorized() {
        return new IdentityException("INVALID_CREDENTIALS", "Invalid credentials", 401);
    }
}
