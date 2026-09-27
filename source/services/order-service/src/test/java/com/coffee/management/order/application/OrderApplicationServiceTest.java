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
    CheckoutPort checkout=mock(CheckoutPort.class);
    JdbcOrderRepository repository=mock(JdbcOrderRepository.class);
    PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    OrderApplicationService service=new OrderApplicationService(catalog,payments,checkout,repository,new TransactionTemplate(manager));
    Actor actor=new Actor(actorId,Set.of("order:create","order:view","order:collect_cash"),Set.of(branch),false);
    OrderApplicationService.Create request=new OrderApplicationService.Create(branch,"POS",List.of(new OrderApplicationService.RequestedItem(variant,2)));
    OrderApplicationServiceTest() { when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus()); when(repository.acquireSaga(any(),any())).thenReturn(true); }

    @Test void pricesFromCatalogAndCreatesAwaitingCash() {
        when(catalog.sellable(variant,branch,"POS","Bearer token")).thenReturn(sellable(true));
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
        when(catalog.sellable(any(),any(),any(),any())).thenReturn(sellable(false));
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
    @Test void checkoutCoordinatesBenefitsStockPaymentAndCommits() {
        UUID id=UUID.randomUUID(),ingredient=UUID.randomUUID(),reservation=UUID.randomUUID(),payment=UUID.randomUUID();
        var pending=new JdbcOrderRepository.Snapshot(id,branch,"POS","AWAITING_CASH","PENDING_CASH",36000,null,0);
        when(repository.find(id)).thenReturn(pending);
        when(repository.startSaga(eq(id),eq("checkout-1"),anyString(),eq("SAVE"),eq(actorId),eq(1000L),eq(40000L)))
                .thenReturn(new JdbcOrderRepository.Saga(id,"checkout-1","hash","STARTED",null,null));
        when(checkout.reserveVoucher(id,branch,"SAVE",36000,"Bearer token")).thenReturn(new CheckoutPort.Benefit(reservation,5000,"RESERVED"));
        when(checkout.reservePoints(id,actorId,1000,"Bearer token")).thenReturn(new CheckoutPort.Benefit(UUID.randomUUID(),1000,"RESERVED"));
        when(repository.applyDiscount(id,5000,1000)).thenReturn(new JdbcOrderRepository.Snapshot(id,branch,"POS","AWAITING_CASH","PENDING_CASH",30000,null,1));
        when(repository.requiredStock(id)).thenReturn(Map.of(ingredient,20L));
        when(repository.sagaSteps(id,"STOCK")).thenReturn(List.of());
        when(checkout.collect(id,40000,"checkout-1:payment","Bearer token")).thenReturn(new CheckoutPort.Payment(payment,true));
        var result=service.checkoutCash(id,new OrderApplicationService.Checkout(actorId,1000,"SAVE",40000),"checkout-1",actor,"Bearer token");
        assertEquals("COMPLETED",result.status());
        verify(checkout).deduct(eq(id),eq(branch),eq(ingredient),eq(20L),contains(ingredient.toString()),eq("Bearer token"));
        verify(checkout).finishVoucher(id,true,"Bearer token");
        verify(checkout).finishPoints(id,true,"Bearer token");
    }
    @Test void checkoutCompensatesReservedBenefitWhenStockFails() {
        UUID id=UUID.randomUUID(),reservation=UUID.randomUUID(),ingredient=UUID.randomUUID();
        var pending=new JdbcOrderRepository.Snapshot(id,branch,"POS","AWAITING_CASH","PENDING_CASH",36000,null,0);
        when(repository.find(id)).thenReturn(pending);
        when(repository.startSaga(eq(id),eq("checkout-2"),anyString(),eq("SAVE"),isNull(),eq(0L),eq(40000L)))
                .thenReturn(new JdbcOrderRepository.Saga(id,"checkout-2","hash","STARTED",null,null));
        when(checkout.reserveVoucher(any(),any(),any(),anyLong(),any())).thenReturn(new CheckoutPort.Benefit(reservation,5000,"RESERVED"));
        when(repository.applyDiscount(id,5000,0)).thenReturn(pending);
        when(repository.requiredStock(id)).thenReturn(Map.of(ingredient,20L));
        when(repository.sagaSteps(id,"STOCK")).thenReturn(List.of());
        when(repository.sagaSteps(id,"VOUCHER")).thenReturn(List.of(new JdbcOrderRepository.SagaStep("VOUCHER",reservation,5000,"DONE")));
        when(checkout.deduct(any(),any(),any(),anyLong(),any(),any())).thenThrow(new OrderException(409,"CHECKOUT_STEP_FAILED","stock"));
        assertThrows(OrderException.class,()->service.checkoutCash(id,new OrderApplicationService.Checkout(null,0,"SAVE",40000),"checkout-2",actor,"Bearer token"));
        verify(checkout).finishVoucher(id,false,"Bearer token");
        verify(repository).clearDiscount(id);
        verify(checkout,never()).collect(any(),anyLong(),any(),any());
    }
    @Test void paymentFailureBecomesPendingAndNeverRestoresStock() {
        UUID id=UUID.randomUUID(),ingredient=UUID.randomUUID();
        var pending=new JdbcOrderRepository.Snapshot(id,branch,"POS","AWAITING_CASH","PENDING_CASH",36000,null,0);
        when(repository.find(id)).thenReturn(pending);
        when(repository.startSaga(eq(id),eq("checkout-3"),anyString(),isNull(),isNull(),eq(0L),eq(40000L)))
                .thenReturn(new JdbcOrderRepository.Saga(id,"checkout-3","hash","STARTED",null,null));
        when(repository.applyDiscount(id,0,0)).thenReturn(pending);
        when(repository.requiredStock(id)).thenReturn(Map.of(ingredient,20L));
        when(repository.sagaSteps(id,"STOCK")).thenReturn(List.of());
        when(checkout.collect(any(),anyLong(),any(),any())).thenThrow(new OrderException(503,"CHECKOUT_DEPENDENCY_UNAVAILABLE","timeout"));
        var ex=assertThrows(OrderException.class,()->service.checkoutCash(id,new OrderApplicationService.Checkout(null,0,null,40000),"checkout-3",actor,"Bearer token"));
        assertEquals(202,ex.status());
        verify(checkout,never()).reverse(any(),any(),any(),anyLong(),any(),any());
        verify(repository,never()).clearDiscount(id);
    }
    private CatalogPort.Sellable sellable(boolean available) {
        return new CatalogPort.Sellable(variant,branch,"POS",18000,3,available,1,
                List.of(new CatalogPort.RecipeIngredient(UUID.randomUUID(),20)));
    }
}
