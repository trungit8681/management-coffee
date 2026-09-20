package com.coffee.management.organization.domain.model;

import java.time.LocalDate;
import java.util.UUID;

public record Employee(UUID id, UUID identityUserId, String employeeCode, String fullName,
                       String email, String phone, LocalDate hireDate, Status status, long version) {
    public enum Status { ACTIVE, ON_LEAVE, TERMINATED }
    public Employee {
        if (identityUserId == null) throw new IllegalArgumentException("identityUserId is required");
        if (employeeCode == null || !employeeCode.matches("[A-Z0-9_-]{2,32}")) throw new IllegalArgumentException("Invalid employee code");
        if (fullName == null || fullName.isBlank()) throw new IllegalArgumentException("fullName is required");
        if (hireDate == null || status == null) throw new IllegalArgumentException("hireDate and status are required");
    }
    public boolean active() { return status == Status.ACTIVE; }
}
