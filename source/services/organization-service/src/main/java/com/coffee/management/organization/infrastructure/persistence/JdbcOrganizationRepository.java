package com.coffee.management.organization.infrastructure.persistence;

import com.coffee.management.organization.application.OrganizationException;
import com.coffee.management.organization.application.port.OrganizationRepository;
import com.coffee.management.organization.domain.model.*;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOrganizationRepository implements OrganizationRepository {
    private final JdbcTemplate jdbc;

    public JdbcOrganizationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UUID createBranch(Branch b, String key, String hash, UUID actor, String correlation) {
        return idempotent("CREATE_BRANCH", key, hash, () -> {
            try {
                jdbc.update(
                        "INSERT INTO branches(id,code,name,address,timezone,opens_at,closes_at,status) VALUES (?,?,?,?,?,?,?,?)",
                        b.id(), b.code(), b.name(), b.address(), b.timezone(), b.opensAt(), b.closesAt(),
                        b.status().name());
            } catch (DataIntegrityViolationException e) {
                throw conflict("BRANCH_CONFLICT", "Branch code already exists or data is invalid");
            }
            event("Branch", b.id(), "BranchCreated", correlation);
            audit("BRANCH_CREATED", actor, b.id(), b.id(), correlation);
            return b.id();
        });
    }

    @Override
    public void updateBranch(UUID id, Branch b, long version, UUID actor, String correlation) {
        int n = jdbc.update(
                "UPDATE branches SET code=?,name=?,address=?,timezone=?,opens_at=?,closes_at=?,status=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND version=?",
                b.code(), b.name(), b.address(), b.timezone(), b.opensAt(), b.closesAt(), b.status().name(), id,
                version);
        if (n == 0)
            throw conflict("BRANCH_VERSION_CONFLICT", "Branch was changed or does not exist");
        event("Branch", id, "BranchUpdated", correlation);
        audit("BRANCH_UPDATED", actor, id, id, correlation);
    }

    @Override
    public Optional<Branch> branch(UUID id) {
        return jdbc.query("SELECT * FROM branches WHERE id=?", this::branchRow, id).stream().findFirst();
    }

    @Override
    public List<Branch> branches() {
        return jdbc.query("SELECT * FROM branches ORDER BY code", this::branchRow);
    }

    private Branch branchRow(ResultSet r, int n) throws SQLException {
        return new Branch(r.getObject("id", UUID.class), r.getString("code"), r.getString("name"),
                r.getString("address"), r.getString("timezone"), r.getObject("opens_at", LocalTime.class),
                r.getObject("closes_at", LocalTime.class), Branch.Status.valueOf(r.getString("status")),
                r.getLong("version"));
    }

    @Override
    public UUID createEmployee(Employee e, String key, String hash, UUID actor, String correlation) {
        return idempotent("CREATE_EMPLOYEE", key, hash, () -> {
            try {
                jdbc.update(
                        "INSERT INTO employees(id,identity_user_id,employee_code,full_name,email,phone,hire_date,status) VALUES (?,?,?,?,?,?,?,?)",
                        e.id(), e.identityUserId(), e.employeeCode(), e.fullName(), e.email(), e.phone(), e.hireDate(),
                        e.status().name());
            } catch (DataIntegrityViolationException x) {
                throw conflict("EMPLOYEE_CONFLICT", "Identity or employee code already exists");
            }
            event("Employee", e.id(), "EmployeeCreated", correlation);
            audit("EMPLOYEE_CREATED", actor, e.id(), null, correlation);
            return e.id();
        });
    }

    @Override
    public Optional<Employee> employee(UUID id) {
        return jdbc.query("SELECT * FROM employees WHERE id=?",
                (r, n) -> new Employee(r.getObject("id", UUID.class), r.getObject("identity_user_id", UUID.class),
                        r.getString("employee_code"), r.getString("full_name"), r.getString("email"),
                        r.getString("phone"), r.getObject("hire_date", LocalDate.class),
                        Employee.Status.valueOf(r.getString("status")), r.getLong("version")),
                id).stream().findFirst();
    }

    @Override
    public UUID assignEmployee(UUID employeeId, UUID branchId, String position, LocalDate from, LocalDate until,
            String key, String hash, UUID actor, String correlation) {
        return idempotent("ASSIGN_EMPLOYEE", key, hash, () -> {
            UUID id = UUID.randomUUID();
            try {
                jdbc.update(
                        "INSERT INTO employee_assignments(id,employee_id,branch_id,position,effective_from,effective_until,assigned_by) VALUES (?,?,?,?,?,?,?)",
                        id, employeeId, branchId, position, from, until, actor);
            } catch (DataIntegrityViolationException e) {
                throw conflict("ASSIGNMENT_CONFLICT", "Assignment overlaps or a reference is invalid");
            }
            event("Employee", employeeId, "EmployeeAssigned", correlation);
            audit("EMPLOYEE_ASSIGNED", actor, employeeId, branchId, correlation);
            return id;
        });
    }

    @Override
    public UUID publishSchedule(UUID branchId, LocalDate weekStart, List<ScheduledEntry> entries, String key,
            String hash, UUID actor, String correlation) {
        return idempotent("PUBLISH_SCHEDULE", key, hash, () -> {
            UUID schedule = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO schedules(id,branch_id,week_start,status,published_by,published_at) VALUES (?,?,?,'PUBLISHED',?,CURRENT_TIMESTAMP)",
                    schedule, branchId, weekStart, actor);
            for (ScheduledEntry e : entries) {
                if (!assigned(e.employeeId(), branchId, e.startsAt()))
                    throw new OrganizationException("EMPLOYEE_NOT_ASSIGNED", "Employee is not assigned to branch", 422);
                if (overlap(e.employeeId(), e.startsAt(), e.endsAt()))
                    throw conflict("SCHEDULE_CONFLICT", "Employee has an overlapping shift");
                new WorkPeriod(e.startsAt(), e.endsAt());
                jdbc.update(
                        "INSERT INTO schedule_entries(id,schedule_id,employee_id,starts_at,ends_at,role) VALUES (?,?,?,?,?,?)",
                        UUID.randomUUID(), schedule, e.employeeId(), ts(e.startsAt()), ts(e.endsAt()), e.role());
            }
            event("Schedule", schedule, "SchedulePublished", correlation);
            audit("SCHEDULE_PUBLISHED", actor, schedule, branchId, correlation);
            return schedule;
        });
    }

    private boolean assigned(UUID employee, UUID branch, Instant at) {
        LocalDate d = at.atZone(ZoneOffset.UTC).toLocalDate();
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM employee_assignments WHERE employee_id=? AND branch_id=? AND effective_from<=? AND (effective_until IS NULL OR effective_until>=?))",
                Boolean.class, employee, branch, d, d));
    }

    private boolean overlap(UUID employee, Instant start, Instant end) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM schedule_entries WHERE employee_id=? AND starts_at<? AND ends_at>?)",
                Boolean.class, employee, ts(end), ts(start)));
    }

    @Override
    public UUID checkIn(UUID employeeId, UUID branchId, UUID entryId, Instant at, String type, String ref,
            boolean approved, String key, String hash, UUID actor, String correlation) {
        return idempotent("CHECK_IN", key, hash, () -> {
            if (!assigned(employeeId, branchId, at) && !approved)
                throw new OrganizationException("ATTENDANCE_EXCEPTION_APPROVAL_REQUIRED",
                        "Employee is not assigned to this branch", 422);
            UUID id = UUID.randomUUID();
            Instant scheduled = entryId == null ? null
                    : jdbc.query(
                            "SELECT starts_at FROM schedule_entries e JOIN schedules s ON s.id=e.schedule_id WHERE e.id=? AND e.employee_id=? AND s.branch_id=?",
                            (r, n) -> r.getTimestamp(1).toInstant(), entryId, employeeId, branchId).stream().findFirst()
                            .orElse(null);
            if (entryId != null && scheduled == null && !approved)
                throw new OrganizationException("ATTENDANCE_EXCEPTION_APPROVAL_REQUIRED", "No matching schedule entry",
                        422);
            int late = scheduled == null ? 0 : (int) Math.max(0, Duration.between(scheduled, at).toMinutes());
            try {
                jdbc.update(
                        "INSERT INTO attendances(id,employee_id,branch_id,schedule_entry_id,checked_in_at,evidence_type,evidence_ref,late_minutes,exception_approved,recorded_by) VALUES (?,?,?,?,?,?,?,?,?,?)",
                        id, employeeId, branchId, entryId, ts(at), type, ref, late, approved, actor);
            } catch (DataIntegrityViolationException e) {
                throw conflict("ATTENDANCE_CONFLICT", "Employee already has an open attendance");
            }
            event("Attendance", id, "EmployeeCheckedIn", correlation);
            audit("EMPLOYEE_CHECKED_IN", actor, id, branchId, correlation);
            return id;
        });
    }

    @Override
    public void checkOut(UUID id, Instant at, UUID actor, String correlation) {
        List<UUID> branches = jdbc.query(
                "UPDATE attendances a SET checked_out_at=?,updated_at=CURRENT_TIMESTAMP WHERE a.id=? AND a.checked_out_at IS NULL AND a.checked_in_at<? AND EXISTS(SELECT 1 FROM employees e WHERE e.id=a.employee_id AND e.identity_user_id=?) RETURNING a.branch_id",
                (r, n) -> r.getObject(1, UUID.class), ts(at), id, ts(at), actor);
        if (branches.isEmpty())
            throw conflict("ATTENDANCE_CONFLICT",
                    "Attendance is not owned by actor, closed, missing, or checkout is invalid");
        event("Attendance", id, "EmployeeCheckedOut", correlation);
        audit("EMPLOYEE_CHECKED_OUT", actor, id, branches.getFirst(), correlation);
    }

    @Override
    public UUID openShift(CashShift s, String key, String hash, String correlation) {
        return idempotent("OPEN_SHIFT", key, hash, () -> {
            try {
                jdbc.update(
                        "INSERT INTO work_shifts(id,branch_id,register_code,opened_by,opened_at,opening_cash,status) VALUES (?,?,?,?,?,?,'OPEN')",
                        s.id(), s.branchId(), s.registerCode(), s.openedBy(), ts(s.openedAt()), s.openingCash());
            } catch (DataIntegrityViolationException e) {
                throw conflict("SHIFT_ALREADY_OPEN", "Register already has an open shift");
            }
            event("WorkShift", s.id(), "WorkShiftOpened", correlation);
            audit("SHIFT_OPENED", s.openedBy(), s.id(), s.branchId(), correlation);
            return s.id();
        });
    }

    @Override
    public CloseResult closeShift(UUID id, long version, BigDecimal sales, BigDecimal in, BigDecimal out,
            BigDecimal refunds, BigDecimal actual, String explanation, UUID approvedBy, UUID actor,
            String correlation) {
        CashShift s = jdbc
                .query("SELECT * FROM work_shifts WHERE id=? FOR UPDATE",
                        (r, n) -> new CashShift(r.getObject("id", UUID.class), r.getObject("branch_id", UUID.class),
                                r.getString("register_code"), r.getObject("opened_by", UUID.class),
                                r.getTimestamp("opened_at").toInstant(), r.getBigDecimal("opening_cash"),
                                CashShift.Status.valueOf(r.getString("status")), r.getLong("version")),
                        id)
                .stream().findFirst()
                .orElseThrow(() -> new OrganizationException("SHIFT_NOT_FOUND", "Shift not found", 404));
        if (s.status() != CashShift.Status.OPEN || s.version() != version)
            throw conflict("SHIFT_VERSION_CONFLICT", "Shift is closed or was changed");
        BigDecimal expected = s.expectedCash(sales, in, out, refunds), variance = actual.subtract(expected);
        if (variance.abs().compareTo(new BigDecimal("100000")) > 0
                && (explanation == null || explanation.isBlank() || approvedBy == null))
            throw new OrganizationException("SHIFT_APPROVAL_REQUIRED",
                    "Large variance requires explanation and approval", 422);
        jdbc.update(
                "UPDATE work_shifts SET cash_sales=?,cash_in=?,cash_out=?,cash_refunds=?,expected_cash=?,actual_cash=?,variance=?,explanation=?,approved_by=?,closed_by=?,closed_at=CURRENT_TIMESTAMP,status='CLOSED',version=version+1 WHERE id=? AND version=?",
                sales, in, out, refunds, expected, actual, variance, explanation, approvedBy, actor, id, version);
        event("WorkShift", id, "WorkShiftClosed", correlation);
        auditShiftClosed(actor, id, s.branchId(), correlation, sales, in, out, refunds, expected, actual, variance,
                approvedBy,
                explanation != null && !explanation.isBlank());
        return new CloseResult(expected, variance);
    }

    private UUID idempotent(String operation, String key, String hash, java.util.function.Supplier<UUID> action) {
        if (key == null || key.isBlank())
            throw new OrganizationException("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required", 400);
        List<Idem> found = jdbc.query(
                "SELECT request_hash,resource_id,completed_at FROM organization_idempotency WHERE operation=? AND idempotency_key=?",
                (r, n) -> new Idem(r.getString(1), r.getObject(2, UUID.class), r.getTimestamp(3)), operation, key);
        if (!found.isEmpty()) {
            Idem i = found.getFirst();
            if (!i.hash.equals(hash) || i.completed == null)
                throw conflict("IDEMPOTENCY_CONFLICT", "Idempotency key is reused or in progress");
            return i.resource;
        }
        jdbc.update(
                "INSERT INTO organization_idempotency(id,operation,idempotency_key,request_hash,expires_at) VALUES (?,?,?,?,CURRENT_TIMESTAMP+INTERVAL '24 hours')",
                UUID.randomUUID(), operation, key, hash);
        UUID id = action.get();
        jdbc.update(
                "UPDATE organization_idempotency SET resource_id=?,completed_at=CURRENT_TIMESTAMP WHERE operation=? AND idempotency_key=?",
                id, operation, key);
        return id;
    }

    private void event(String type, UUID id, String event, String correlation) {
        jdbc.update(
                "INSERT INTO organization_outbox(id,aggregate_type,aggregate_id,event_type,payload,correlation_id) VALUES (?,?,?,?,CAST(? AS jsonb),?)",
                UUID.randomUUID(), type, id, event, "{\"aggregateId\":\"" + id + "\"}", correlation);
    }

    private void audit(String type, UUID actor, UUID subject, UUID branch, String correlation) {
        jdbc.update(
                "INSERT INTO organization_audit(id,event_type,actor_user_id,subject_id,branch_id,correlation_id) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID(), type, actor, subject, branch, correlation);
    }

    private void auditShiftClosed(UUID actor, UUID shiftId, UUID branchId, String correlation,
            BigDecimal sales, BigDecimal cashIn, BigDecimal cashOut, BigDecimal refunds,
            BigDecimal expected, BigDecimal actual, BigDecimal variance, UUID approvedBy, boolean explanationProvided) {
        jdbc.update(
                "INSERT INTO organization_audit(id,event_type,actor_user_id,subject_id,branch_id,correlation_id,metadata) VALUES (?,'SHIFT_CLOSED',?,?,?,?,jsonb_build_object('cashSales',?,'cashIn',?,'cashOut',?,'cashRefunds',?,'expectedCash',?,'actualCash',?,'variance',?,'approvedBy',?,'explanationProvided',?))",
                UUID.randomUUID(), actor, shiftId, branchId, correlation, sales, cashIn, cashOut, refunds,
                expected, actual, variance, approvedBy, explanationProvided);
    }

    private static OrganizationException conflict(String code, String message) {
        return new OrganizationException(code, message, 409);
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }

    private record Idem(String hash, UUID resource, Timestamp completed) {
    }
}
