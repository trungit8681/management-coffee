package com.coffee.management.payment.domain;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CashPaymentTest {
    private final UUID order = UUID.randomUUID(), branch = UUID.randomUUID();
    @Test void computesChange() { assertEquals(2000, new CashPayment(order, branch, 18000, 20000).changeVnd()); }
    @Test void rejectsInsufficientCash() { assertThrows(IllegalArgumentException.class, () -> new CashPayment(order, branch, 18000, 17999)); }
    @Test void rejectsInvalidTotal() { assertThrows(IllegalArgumentException.class, () -> new CashPayment(order, branch, 0, 100)); }
}
