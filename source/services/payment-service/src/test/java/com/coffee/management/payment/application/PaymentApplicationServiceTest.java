package com.coffee.management.payment.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.coffee.management.payment.application.port.OrderPort;
import com.coffee.management.payment.infrastructure.persistence.JdbcPaymentRepository;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class PaymentApplicationServiceTest {
    UUID order=UUID.randomUUID(),branch=UUID.randomUUID(),actorId=UUID.randomUUID();
    OrderPort orders=mock(OrderPort.class);
    JdbcPaymentRepository repository=mock(JdbcPaymentRepository.class);
    PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    PaymentApplicationService service=new PaymentApplicationService(orders,repository,new TransactionTemplate(manager));
    Actor actor=new Actor(actorId,Set.of("payment:collect_cash"),Set.of(branch),false);
    PaymentApplicationServiceTest() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(orders.quote(order,"Bearer token")).thenReturn(new OrderPort.CashQuote(order,branch,18000,"AWAITING_CASH"));
    }
    @Test void collectsOnlyVerifiedAmount() {
        var receipt=new JdbcPaymentRepository.Receipt(UUID.randomUUID(),order,branch,18000,20000,2000);
        when(repository.record(any(),eq("key"),anyString(),eq(actorId))).thenReturn(receipt);
        assertEquals(receipt,service.collect(order,20000,"key",actor,"Bearer token"));
    }
    @Test void rejectsInsufficientCash() {
        assertThrows(IllegalArgumentException.class,()->service.collect(order,17999,"key",actor,"Bearer token"));
        verify(repository,never()).record(any(),any(),any(),any());
    }
    @Test void rejectsOutsideBranch() {
        var denied=new Actor(actorId,actor.permissions(),Set.of(),false);
        assertThrows(SecurityException.class,()->service.collect(order,20000,"key",denied,"Bearer token"));
        verify(repository,never()).record(any(),any(),any(),any());
    }
    @Test void replayNeedsNoOrderNetworkCall() {
        var receipt=new JdbcPaymentRepository.Receipt(UUID.randomUUID(),order,branch,18000,20000,2000);
        when(repository.replay("key",order,20000,actorId)).thenReturn(receipt);
        assertEquals(receipt,service.collect(order,20000,"key",actor,"Bearer token"));
        verifyNoInteractions(orders);
    }
    @Test void orderFailureNeverRecordsCash() {
        when(orders.quote(order,"Bearer token")).thenThrow(new PaymentException(503,"ORDER_UNAVAILABLE","Unavailable"));
        assertThrows(PaymentException.class,()->service.collect(order,20000,"key",actor,"Bearer token"));
        verify(repository,never()).record(any(),any(),any(),any());
    }
    @Test void refundRequiresApprovedOrder() {
        var manager=new Actor(actorId,Set.of("payment:refund_cash"),Set.of(branch),false);
        when(orders.cancellation(order,"Bearer token")).thenReturn(new OrderPort.CancellationQuote(order,branch,18000,"CONFIRMED"));
        assertEquals("ORDER_NOT_AWAITING_REFUND",assertThrows(PaymentException.class,
                ()->service.refund(order,"Customer request","refund-key",manager,"Bearer token")).code());
        verify(repository,never()).recordRefund(any(),any(),any(),any());
    }
    @Test void refundReplayDoesNotCallOrderAgain() {
        var manager=new Actor(actorId,Set.of("payment:refund_cash"),Set.of(branch),false);
        var refund=new JdbcPaymentRepository.RefundReceipt(UUID.randomUUID(),order,branch,18000);
        when(repository.replayRefund("refund-key",order,"Customer request",actorId)).thenReturn(refund);
        assertEquals(refund,service.refund(order,"Customer request","refund-key",manager,"Bearer token"));
        verify(orders,never()).cancellation(any(),any());
    }
    @Test void cancelledOrderWithRecordedCashCanBeCompensated() {
        var manager=new Actor(actorId,Set.of("payment:refund_cash"),Set.of(branch),false);
        when(orders.cancellation(order,"Bearer token")).thenReturn(new OrderPort.CancellationQuote(order,branch,18000,"CANCELLED"));
        var refund=new JdbcPaymentRepository.RefundReceipt(UUID.randomUUID(),order,branch,18000);
        when(repository.recordRefund(any(),eq("refund-key"),anyString(),eq(actorId))).thenReturn(refund);
        assertEquals(refund,service.refund(order,"Race compensation","refund-key",manager,"Bearer token"));
    }
}
