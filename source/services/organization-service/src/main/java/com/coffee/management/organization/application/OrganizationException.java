package com.coffee.management.organization.application;

public class OrganizationException extends RuntimeException {
    private final String code; private final int status;
    public OrganizationException(String code, String message, int status) { super(message); this.code=code; this.status=status; }
    public String code(){ return code; } public int status(){ return status; }
}
