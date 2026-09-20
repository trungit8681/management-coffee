package com.coffee.management.loyalty.domain;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class PointsTest {
    @Test void acceptsPositivePoints() { assertEquals(1,new Points(1).value()); }
    @Test void rejectsZero() { assertThrows(IllegalArgumentException.class,()->new Points(0)); }
}
