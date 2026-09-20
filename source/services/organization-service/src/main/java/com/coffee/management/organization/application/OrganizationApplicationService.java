package com.coffee.management.organization.application;

import com.coffee.management.organization.application.port.OrganizationRepository;
import com.coffee.management.organization.domain.model.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrganizationApplicationService {
    private final OrganizationRepository repository;

    public OrganizationApplicationService(OrganizationRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public UUID createBranch(CreateBranch command, String key, Actor actor, String correlation) {
        actor.require("organization:manage_branch", null);
        UUID id = UUID.randomUUID();
        Branch branch = new Branch(id, command.code(), command.name(), command.address(), command.timezone(),
                command.opensAt(), command.closesAt(), Branch.Status.OPEN, 0);
        return repository.createBranch(branch, key, hash(command), actor.userId(), correlation);
    }

    @Transactional
    public void updateBranch(UUID id, UpdateBranch command, long version, Actor actor, String correlation) {
        actor.require("organization:manage_branch", id);
        repository.updateBranch(
                id, new Branch(id, command.code(), command.name(), command.address(), command.timezone(),
                        command.opensAt(), command.closesAt(), command.status(), version),
                version, actor.userId(), correlation);
    }

    @Transactional(readOnly = true)
    public Branch branch(UUID id, Actor actor) {
        actor.require("organization:view_branch", id);
        return repository.branch(id).orElseThrow(() -> notFound("Branch"));
    }

    @Transactional(readOnly = true)
    public List<Branch> branches(Actor actor) {
        actor.require("organization:view_branch", null);
        return repository.branches().stream().filter(b -> actor.globalScope() || actor.branchScopes().contains(b.id()))
                .toList();
    }

    @Transactional
    public UUID createEmployee(CreateEmployee command, String key, Actor actor, String correlation) {
        actor.require("organization:manage_employee", null);
        UUID id = UUID.randomUUID();
        Employee employee = new Employee(id, command.identityUserId(), command.employeeCode().toUpperCase(),
                command.fullName(), command.email(), command.phone(), command.hireDate(), Employee.Status.ACTIVE, 0);
        return repository.createEmployee(employee, key, hash(command), actor.userId(), correlation);
    }

    @Transactional
    public UUID assign(UUID employeeId, AssignEmployee command, String key, Actor actor, String correlation) {
        actor.require("organization:assign_employee", command.branchId());
        Employee employee = repository.employee(employeeId).orElseThrow(() -> notFound("Employee"));
        if (!employee.active())
            throw new OrganizationException("EMPLOYEE_INACTIVE", "Only active employees can be assigned", 422);
        return repository.assignEmployee(employeeId, command.branchId(), command.position(), command.effectiveFrom(),
                command.effectiveUntil(), key, hash(command), actor.userId(), correlation);
    }

    @Transactional
    public UUID publishSchedule(PublishSchedule command, String key, Actor actor, String correlation) {
        actor.require("organization:manage_schedule", command.branchId());
        if (!command.weekStart().getDayOfWeek().equals(java.time.DayOfWeek.MONDAY))
            throw new OrganizationException("INVALID_WEEK_START", "weekStart must be Monday", 422);
        return repository.publishSchedule(command.branchId(), command.weekStart(), command.entries(), key,
                hash(command), actor.userId(), correlation);
    }

    @Transactional
    public UUID checkIn(CheckIn command, String key, Actor actor, String correlation) {
        if (!actor.userId().equals(command.identityUserId()))
            actor.require("organization:record_attendance", command.branchId());
        else
            actor.require("organization:self_attendance", command.branchId());
        Employee employee = repository.employee(command.employeeId()).orElseThrow(() -> notFound("Employee"));
        if (!employee.identityUserId().equals(command.identityUserId()))
            throw new OrganizationException("EMPLOYEE_IDENTITY_MISMATCH", "Employee does not belong to identity", 403);
        return repository.checkIn(command.employeeId(), command.branchId(), command.scheduleEntryId(),
                command.occurredAt(), command.evidenceType(), command.evidenceRef(), command.exceptionApproved(), key,
                hash(command), actor.userId(), correlation);
    }

    @Transactional
    public void checkOut(UUID attendanceId, Instant at, Actor actor, String correlation) {
        actor.require("organization:self_attendance", null);
        repository.checkOut(attendanceId, at, actor.userId(), correlation);
    }

    @Transactional
    public UUID openShift(OpenShift command, String key, Actor actor, String correlation) {
        actor.require("organization:open_shift", command.branchId());
        CashShift shift = new CashShift(UUID.randomUUID(), command.branchId(), command.registerCode(), actor.userId(),
                Instant.now(), command.openingCash(), CashShift.Status.OPEN, 0);
        return repository.openShift(shift, key, hash(command), correlation);
    }

    @Transactional
    public OrganizationRepository.CloseResult closeShift(UUID id, CloseShift command, long version, Actor actor,
            String correlation) {
        actor.require("organization:close_shift", command.branchId());
        if (command.approvedBy() != null) {
            if (!actor.userId().equals(command.approvedBy()))
                throw new OrganizationException("INVALID_APPROVER", "Approver must be the authenticated actor", 403);
            actor.require("organization:approve_shift_variance", command.branchId());
        }
        return repository.closeShift(id, version, command.cashSales(), command.cashIn(), command.cashOut(),
                command.cashRefunds(), command.actualCash(), command.explanation(), command.approvedBy(),
                actor.userId(), correlation);
    }

    private static OrganizationException notFound(String subject) {
        return new OrganizationException(subject.toUpperCase() + "_NOT_FOUND", subject + " not found", 404);
    }

    private static String hash(Object value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public record CreateBranch(String code, String name, String address, String timezone, LocalTime opensAt,
            LocalTime closesAt) {
    }

    public record UpdateBranch(String code, String name, String address, String timezone, LocalTime opensAt,
            LocalTime closesAt, Branch.Status status) {
    }

    public record CreateEmployee(UUID identityUserId, String employeeCode, String fullName, String email, String phone,
            LocalDate hireDate) {
    }

    public record AssignEmployee(UUID branchId, String position, LocalDate effectiveFrom, LocalDate effectiveUntil) {
    }

    public record PublishSchedule(UUID branchId, LocalDate weekStart,
            List<OrganizationRepository.ScheduledEntry> entries) {
    }

    public record CheckIn(UUID employeeId, UUID identityUserId, UUID branchId, UUID scheduleEntryId, Instant occurredAt,
            String evidenceType, String evidenceRef, boolean exceptionApproved) {
    }

    public record OpenShift(UUID branchId, String registerCode, BigDecimal openingCash) {
    }

    public record CloseShift(UUID branchId, BigDecimal cashSales, BigDecimal cashIn, BigDecimal cashOut,
            BigDecimal cashRefunds, BigDecimal actualCash, String explanation, UUID approvedBy) {
    }
}
