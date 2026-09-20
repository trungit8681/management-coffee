package com.coffee.management.organization.application.port;

import com.coffee.management.organization.domain.model.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrganizationRepository {
    UUID createBranch(Branch branch, String key, String hash, UUID actor, String correlation);

    void updateBranch(UUID id, Branch branch, long expectedVersion, UUID actor, String correlation);

    Optional<Branch> branch(UUID id);

    List<Branch> branches();

    UUID createEmployee(Employee employee, String key, String hash, UUID actor, String correlation);

    Optional<Employee> employee(UUID id);

    UUID assignEmployee(UUID employeeId, UUID branchId, String position, LocalDate from, LocalDate until, String key,
            String hash, UUID actor, String correlation);

    UUID publishSchedule(UUID branchId, LocalDate weekStart, List<ScheduledEntry> entries, String key, String hash,
            UUID actor, String correlation);

    UUID checkIn(UUID employeeId, UUID branchId, UUID scheduleEntryId, Instant occurredAt, String evidenceType,
            String evidenceRef, boolean exceptionApproved, String key, String hash, UUID actor, String correlation);

    void checkOut(UUID attendanceId, Instant occurredAt, UUID actor, String correlation);

    UUID openShift(CashShift shift, String key, String hash, String correlation);

    CloseResult closeShift(UUID shiftId, long version, BigDecimal sales, BigDecimal cashIn, BigDecimal cashOut,
            BigDecimal refunds, BigDecimal actual, String explanation, UUID approvedBy, UUID actor, String correlation);

    record ScheduledEntry(UUID employeeId, Instant startsAt, Instant endsAt, String role) {
    }

    record CloseResult(BigDecimal expectedCash, BigDecimal variance) {
    }
}
