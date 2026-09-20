package com.coffee.management.order.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.coffee.management.order.application.port.*;
import com.coffee.management.order.domain.Order;
import com.coffee.management.order.infrastructure.persistence.JdbcOrderRepository;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class OrderApplicationServiceTest {
    UUID branch=UUID.randomUUID(), variant=UUID.randomUUID(), actorId=UUID.randomUUID();
    CatalogPort catalog=mock(CatalogPort.class);
    PaymentPort payments=mock(PaymentPort.class);
    JdbcOrderRepository repository=mock(JdbcOrderRepository.class);
    PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    OrderApplicationService service=new OrderApplicationService(catalog,payments,repository,new TransactionTemplate(manager));
    Actor actor=new Actor(actorId,Set.of("order:create","order:view","order:collect_cash"),Set.of(branch),false);
    OrderApplicationService.Create request=new OrderApplicationService.Create(branch,"POS",List.of(new OrderApplicationService.RequestedItem(variant,2)));
    OrderApplicationServiceTest() { when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus()); }

    @Test void pricesFromCatalogAndCreatesAwaitingCash() {
        when(catalog.sellable(variant,branch,"POS","Bearer token")).thenReturn(new CatalogPort.Sellable(variant,branch,"POS",18000,3,true));
        var snapshot=new JdbcOrderRepository.Snapshot(UUID.randomUUID(),branch,"POS","AWAITING_CASH","PENDING_CASH",36000,null,0);
        when(repository.create(any(Order.class),anyList(),eq(actorId),eq("key"),anyString())).thenAnswer(invocation -> {
            Order order=invocation.getArgument(0);
            assertEquals(36000,order.totalVnd()); assertEquals("AWAITING_CASH",order.status()); return snapshot;
        });
        assertEquals(snapshot,service.create(request,"key",actor,"Bearer token"));
        verify(repository).create(any(Order.class),anyList(),eq(actorId),eq("key"),anyString());
    }
    @Test void rejectsOutsideBranch() {
        var other=new OrderApplicationService.Create(UUID.randomUUID(),"POS",request.items());
        assertThrows(SecurityException.class,()->service.create(other,"key",actor,"Bearer token"));
        verifyNoInteractions(catalog);
    }
    @Test void rejectsUnavailableVariant() {
        when(catalog.sellable(any(),any(),any(),any())).thenReturn(new CatalogPort.Sellable(variant,branch,"POS",18000,3,false));
        assertEquals("ITEM_NOT_SELLABLE",assertThrows(OrderException.class,()->service.create(request,"key",actor,"Bearer token")).code());
        verify(repository,never()).create(any(),anyList(),any(),any(),any());
    }
    @Test void duplicateReturnsOriginalWithoutRepricing() {
        var original=new JdbcOrderRepository.Snapshot(UUID.randomUUID(),branch,"POS","CONFIRMED","PAID",36000,UUID.randomUUID(),1);
        when(repository.byKey(eq("key"),anyString())).thenReturn(original);
        assertEquals(original,service.create(request,"key",actor,"Bearer token"));
        verifyNoInteractions(catalog);
    }
    @Test void paymentReceiptMustMatchBeforeConfirm() {
        UUID id=UUID.randomUUID(),paymentId=UUID.randomUUID();
        when(repository.find(id)).thenReturn(new JdbcOrderRepository.Snapshot(id,branch,"POS","AWAITING_CASH","PENDING_CASH",36000,null,0));
        when(payments.receipt(id,"Bearer token")).thenReturn(new PaymentPort.Receipt(paymentId,id,branch,35000,40000,5000));
        assertEquals("PAYMENT_MISMATCH",assertThrows(OrderException.class,()->service.confirm(id,paymentId,actor,"Bearer token")).code());
        verify(repository,never()).confirm(any(),any(),anyLong(),any());
    }
    @Test void paidCancellationNeedsApprovalPermission() {
        UUID id=UUID.randomUUID();
        when(repository.find(id)).thenReturn(new JdbcOrderRepository.Snapshot(id,branch,"POS","CONFIRMED","PAID",36000,UUID.randomUUID(),1));
        var cashier=new Actor(actorId,Set.of("order:cancel"),Set.of(branch),false);
        assertThrows(SecurityException.class,()->service.cancel(id,1,"Customer request",cashier));
        verify(repository,never()).cancel(any(),anyLong(),any(),any());
    }
    @Test void refundReceiptMustMatchBeforeClosingOrder() {
        UUID id=UUID.randomUUID(),refundId=UUID.randomUUID();
        when(repository.find(id)).thenReturn(new JdbcOrderRepository.Snapshot(id,branch,"POS","REFUND_PENDING","PAID",36000,UUID.randomUUID(),2));
        when(payments.refund(id,"Bearer token")).thenReturn(new PaymentPort.Refund(refundId,id,branch,35000));
        var manager=new Actor(actorId,Set.of("order:approve_cancel"),Set.of(branch),false);
        assertEquals("REFUND_MISMATCH",assertThrows(OrderException.class,()->service.completeRefund(id,refundId,manager,"Bearer token")).code());
        verify(repository,never()).completeRefund(any(),any(),any(),anyLong());
    }
}
