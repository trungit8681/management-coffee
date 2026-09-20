package com.coffee.management.organization.interfaces.rest;

import com.coffee.management.organization.application.*;
import com.coffee.management.organization.application.OrganizationApplicationService.*;
import com.coffee.management.organization.domain.model.Branch;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organization")
public class OrganizationController {
    private final OrganizationApplicationService service;

    public OrganizationController(OrganizationApplicationService service) {
        this.service = service;
    }

    @PostMapping("/branches")
    @ResponseStatus(HttpStatus.CREATED)
    public IdResponse createBranch(@Valid @RequestBody BranchCreate b, @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor a,
            @RequestHeader(value = "X-Correlation-Id", defaultValue = "unknown") String c) {
        return new IdResponse(service.createBranch(
                new CreateBranch(b.code, b.name, b.address, b.timezone, b.opensAt, b.closesAt), key, a, c));
    }

    @PutMapping("/branches/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateBranch(@PathVariable UUID id, @Valid @RequestBody BranchUpdate b,
            @RequestHeader("If-Match") long version, @AuthenticationPrincipal Actor a,
            @RequestHeader(value = "X-Correlation-Id", defaultValue = "unknown") String c) {
        service.updateBranch(id,
                new UpdateBranch(b.code, b.name, b.address, b.timezone, b.opensAt, b.closesAt, b.status), version, a,
                c);
    }

    @GetMapping("/branches/{id}")
    public Branch branch(@PathVariable UUID id, @AuthenticationPrincipal Actor a) {
        return service.branch(id, a);
    }

    @GetMapping("/branches")
    public List<Branch> branches(@AuthenticationPrincipal Actor a) {
        return service.branches(a);
    }

    @PostMapping("/employees")
    @ResponseStatus(HttpStatus.CREATED)
    public IdResponse employee(@Valid @RequestBody EmployeeCreate b, @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor a,
            @RequestHeader(value = "X-Correlation-Id", defaultValue = "unknown") String c) {
        return new IdResponse(service.createEmployee(
                new CreateEmployee(b.identityUserId, b.employeeCode, b.fullName, b.email, b.phone, b.hireDate), key, a,
                c));
    }

    @PostMapping("/employees/{id}/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    public IdResponse assign(@PathVariable UUID id, @Valid @RequestBody AssignmentCreate b,
            @RequestHeader("Idempotency-Key") String key, @AuthenticationPrincipal Actor a,
            @RequestHeader(value = "X-Correlation-Id", defaultValue = "unknown") String c) {
        return new IdResponse(service.assign(id,
                new AssignEmployee(b.branchId, b.position, b.effectiveFrom, b.effectiveUntil), key, a, c));
    }

    @PostMapping("/schedules")
    @ResponseStatus(HttpStatus.CREATED)
    public IdResponse schedule(@Valid @RequestBody ScheduleCreate b, @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor a,
            @RequestHeader(value = "X-Correlation-Id", defaultValue = "unknown") String c) {
        return new IdResponse(service.publishSchedule(new PublishSchedule(b.branchId, b.weekStart, b.entries.stream()
                .map(e -> new com.coffee.management.organization.application.port.OrganizationRepository.ScheduledEntry(
                        e.employeeId, e.startsAt, e.endsAt, e.role))
                .toList()), key, a, c));
    }

    @PostMapping("/attendance/check-ins")
    @ResponseStatus(HttpStatus.CREATED)
    public IdResponse checkIn(@Valid @RequestBody AttendanceCheckIn b, @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor a,
            @RequestHeader(value = "X-Correlation-Id", defaultValue = "unknown") String c) {
        return new IdResponse(service.checkIn(new CheckIn(b.employeeId, b.identityUserId, b.branchId, b.scheduleEntryId,
                b.occurredAt, b.evidenceType, b.evidenceRef, b.exceptionApproved), key, a, c));
    }

    @PostMapping("/attendance/{id}/check-out")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void checkOut(@PathVariable UUID id, @Valid @RequestBody AttendanceCheckOut b,
            @AuthenticationPrincipal Actor a,
            @RequestHeader(value = "X-Correlation-Id", defaultValue = "unknown") String c) {
        service.checkOut(id, b.occurredAt, a, c);
    }

    @PostMapping("/work-shifts")
    @ResponseStatus(HttpStatus.CREATED)
    public IdResponse open(@Valid @RequestBody ShiftOpen b, @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Actor a,
            @RequestHeader(value = "X-Correlation-Id", defaultValue = "unknown") String c) {
        return new IdResponse(service.openShift(new OpenShift(b.branchId, b.registerCode, b.openingCash), key, a, c));
    }

    @PostMapping("/work-shifts/{id}/close")
    public Object close(@PathVariable UUID id, @Valid @RequestBody ShiftClose b,
            @RequestHeader("If-Match") long version, @AuthenticationPrincipal Actor a,
            @RequestHeader(value = "X-Correlation-Id", defaultValue = "unknown") String c) {
        return service.closeShift(id, new CloseShift(b.branchId, b.cashSales, b.cashIn, b.cashOut, b.cashRefunds,
                b.actualCash, b.explanation, b.approvedBy), version, a, c);
    }

    public record IdResponse(UUID id) {
    }

    public record BranchCreate(@NotBlank @Size(max = 32) String code, @NotBlank @Size(max = 160) String name,
            @NotBlank @Size(max = 500) String address, @NotBlank @Size(max = 80) String timezone,
            @NotNull LocalTime opensAt, @NotNull LocalTime closesAt) {
    }

    public record BranchUpdate(@NotBlank String code, @NotBlank String name, @NotBlank String address,
            @NotBlank String timezone, @NotNull LocalTime opensAt, @NotNull LocalTime closesAt,
            @NotNull Branch.Status status) {
    }

    public record EmployeeCreate(@NotNull UUID identityUserId, @NotBlank String employeeCode, @NotBlank String fullName,
            @Email String email, String phone, @NotNull LocalDate hireDate) {
    }

    public record AssignmentCreate(@NotNull UUID branchId, @NotBlank String position, @NotNull LocalDate effectiveFrom,
            LocalDate effectiveUntil) {
    }

    public record ScheduleEntry(@NotNull UUID employeeId, @NotNull Instant startsAt, @NotNull Instant endsAt,
            @NotBlank String role) {
    }

    public record ScheduleCreate(@NotNull UUID branchId, @NotNull LocalDate weekStart,
            @NotEmpty List<@Valid ScheduleEntry> entries) {
    }

    public record AttendanceCheckIn(@NotNull UUID employeeId, @NotNull UUID identityUserId, @NotNull UUID branchId,
            UUID scheduleEntryId, @NotNull Instant occurredAt,
            @Pattern(regexp = "PIN|QR|GPS|DEVICE") String evidenceType, String evidenceRef, boolean exceptionApproved) {
    }

    public record AttendanceCheckOut(@NotNull Instant occurredAt) {
    }

    public record ShiftOpen(@NotNull UUID branchId, @NotBlank String registerCode,
            @NotNull @PositiveOrZero BigDecimal openingCash) {
    }

    public record ShiftClose(@NotNull UUID branchId, @NotNull @PositiveOrZero BigDecimal cashSales,
            @NotNull @PositiveOrZero BigDecimal cashIn, @NotNull @PositiveOrZero BigDecimal cashOut,
            @NotNull @PositiveOrZero BigDecimal cashRefunds, @NotNull @PositiveOrZero BigDecimal actualCash,
            String explanation, UUID approvedBy) {
    }
}
