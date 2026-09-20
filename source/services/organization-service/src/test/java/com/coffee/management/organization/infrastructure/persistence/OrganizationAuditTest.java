package com.coffee.management.organization.infrastructure.persistence;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.coffee.management.organization.application.OrganizationException;
import com.coffee.management.organization.domain.model.CashShift;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class OrganizationAuditTest {
    @Test
    void checkoutAuditsTheAttendanceAndItsBranchOnlyAfterACompletedUpdate() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcOrganizationRepository repository = new JdbcOrganizationRepository(jdbc);
        UUID attendance = UUID.randomUUID(), branch = UUID.randomUUID(), actor = UUID.randomUUID();
        Instant at = Instant.parse("2026-09-14T09:00:00Z");

        when(jdbc.query(startsWith("UPDATE attendances"), any(RowMapper.class), any(), any(), any(), any()))
                .thenReturn(List.of(branch));
        repository.checkOut(attendance, at, actor, "checkout-1");

        verify(jdbc).update(startsWith("INSERT INTO organization_audit"), any(), eq("EMPLOYEE_CHECKED_OUT"),
                eq(actor), eq(attendance), eq(branch), eq("checkout-1"));

        clearInvocations(jdbc);
        when(jdbc.query(startsWith("UPDATE attendances"), any(RowMapper.class), any(), any(), any(), any()))
                .thenReturn(List.of());
        assertThatThrownBy(() -> repository.checkOut(attendance, at, actor, "checkout-2"))
                .isInstanceOf(OrganizationException.class);
        verify(jdbc, never()).update(startsWith("INSERT INTO organization_audit"), any(Object[].class));
    }

    @Test
    void shiftClosureAuditsReconciliationAndApproverWithoutCopyingExplanation() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcOrganizationRepository repository = new JdbcOrganizationRepository(jdbc);
        UUID shiftId = UUID.randomUUID(), branch = UUID.randomUUID(), actor = UUID.randomUUID();
        CashShift shift = new CashShift(shiftId, branch, "POS-1", actor, Instant.now(),
                new BigDecimal("500000"), CashShift.Status.OPEN, 0);
        when(jdbc.query(startsWith("SELECT * FROM work_shifts"), any(RowMapper.class), eq(shiftId)))
                .thenReturn(List.of(shift));

        repository.closeShift(shiftId, 0, new BigDecimal("1000000"), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("1500000"), "Counted at register", actor, actor, "close-1");

        verify(jdbc).update(contains("jsonb_build_object"), any(), eq(actor), eq(shiftId), eq(branch),
                eq("close-1"), eq(new BigDecimal("1000000")), eq(BigDecimal.ZERO), eq(BigDecimal.ZERO),
                eq(BigDecimal.ZERO), eq(new BigDecimal("1500000")), eq(new BigDecimal("1500000")),
                eq(BigDecimal.ZERO), eq(actor), eq(true));
    }
}
