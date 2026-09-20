package com.coffee.management.procurement.domain;
import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
class PurchaseOrderTest {
    UUID id=UUID.randomUUID(), branch=UUID.randomUUID(), supplier=UUID.randomUUID(), ingredient=UUID.randomUUID();
    @Test void computesTotal() { assertEquals(10000,new PurchaseOrder(id,branch,supplier,ingredient,5,2000,"PENDING").totalCostVnd()); }
    @Test void rejectsInvalidQuantity() { assertThrows(IllegalArgumentException.class,()->new PurchaseOrder(id,branch,supplier,ingredient,0,2000,"PENDING")); }
}
