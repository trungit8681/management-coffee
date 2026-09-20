package com.coffee.management.organization.domain.model;

import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;

public record Branch(UUID id, String code, String name, String address, String timezone,
                     LocalTime opensAt, LocalTime closesAt, Status status, long version) {
    public enum Status { OPEN, TEMPORARILY_CLOSED, INACTIVE }
    public Branch {
        code = required(code, "code").toUpperCase();
        name = required(name, "name");
        address = required(address, "address");
        timezone = required(timezone, "timezone");
        Objects.requireNonNull(opensAt, "opensAt"); Objects.requireNonNull(closesAt, "closesAt"); Objects.requireNonNull(status, "status");
        if (!code.matches("[A-Z][A-Z0-9_-]{1,31}")) throw new IllegalArgumentException("Invalid branch code");
        if (opensAt.equals(closesAt)) throw new IllegalArgumentException("Opening and closing times must differ");
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
    }
    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
