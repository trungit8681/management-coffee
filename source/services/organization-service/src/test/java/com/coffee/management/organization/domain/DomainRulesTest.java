package com.coffee.management.organization.domain;

import static org.assertj.core.api.Assertions.*;
import com.coffee.management.organization.application.*;
import com.coffee.management.organization.domain.model.*;
import java.math.BigDecimal;import java.time.*;import java.util.Set;import java.util.UUID;import org.junit.jupiter.api.Test;
class DomainRulesTest {
 @Test void calculatesCashReconciliation(){var s=new CashShift(UUID.randomUUID(),UUID.randomUUID(),"POS-1",UUID.randomUUID(),Instant.now(),new BigDecimal("100000"),CashShift.Status.OPEN,0);assertThat(s.expectedCash(new BigDecimal("500000"),new BigDecimal("10000"),new BigDecimal("5000"),new BigDecimal("20000"))).isEqualByComparingTo("585000");}
 @Test void rejectsNegativeCash(){assertThatThrownBy(()->new CashShift(UUID.randomUUID(),UUID.randomUUID(),"POS",UUID.randomUUID(),Instant.now(),new BigDecimal("-1"),CashShift.Status.OPEN,0)).isInstanceOf(IllegalArgumentException.class);}
 @Test void detectsOverlappingPeriods(){Instant t=Instant.parse("2026-09-07T01:00:00Z");assertThat(new WorkPeriod(t,t.plusSeconds(3600)).overlaps(new WorkPeriod(t.plusSeconds(1800),t.plusSeconds(7200)))).isTrue();}
 @Test void enforcesBranchScope(){UUID branch=UUID.randomUUID();Actor actor=new Actor(UUID.randomUUID(),Set.of("organization:manage_branch"),Set.of(),false);assertThatThrownBy(()->actor.require("organization:manage_branch",branch)).isInstanceOf(OrganizationException.class).extracting("code").isEqualTo("BRANCH_SCOPE_DENIED");}
 @Test void permitsAuthorizedBranch(){UUID branch=UUID.randomUUID();new Actor(UUID.randomUUID(),Set.of("organization:manage_branch"),Set.of(branch),false).require("organization:manage_branch",branch);}
}
