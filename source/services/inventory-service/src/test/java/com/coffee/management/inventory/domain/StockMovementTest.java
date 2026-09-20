package com.coffee.management.inventory.domain;
import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
class StockMovementTest {
    UUID branch=UUID.randomUUID(), ingredient=UUID.randomUUID(), reference=UUID.randomUUID();
    @Test void signsLedgerEntries() {
        assertEquals(10,new StockMovement(branch,ingredient,10,"IN",reference).signedQuantity());
        assertEquals(-10,new StockMovement(branch,ingredient,10,"OUT",reference).signedQuantity());
    }
    @Test void rejectsNonPositiveQuantity() { assertThrows(IllegalArgumentException.class,()->new StockMovement(branch,ingredient,0,"OUT",reference)); }
}
