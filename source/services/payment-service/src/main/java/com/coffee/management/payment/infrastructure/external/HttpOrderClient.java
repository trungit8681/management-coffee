package com.coffee.management.payment.infrastructure.external;

import com.coffee.management.payment.application.PaymentException;
import com.coffee.management.payment.application.port.OrderPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class HttpOrderClient implements OrderPort {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper mapper;
    private final URI base;

    public HttpOrderClient(ObjectMapper mapper, @Value("${payment.order-base-url}") String base) {
        this.mapper = mapper;
        this.base = URI.create(base.endsWith("/") ? base : base + "/");
    }

    public CashQuote quote(UUID orderId, String bearer) {
        try {
            var req = HttpRequest.newBuilder(base.resolve("api/v1/orders/" + orderId + "/cash-quote"))
                    .timeout(Duration.ofSeconds(4)).header("Authorization", bearer).GET().build();
            var res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 404)
                throw new PaymentException(404, "ORDER_NOT_FOUND", "Order not found");
            if (res.statusCode() != 200)
                throw new PaymentException(503, "ORDER_UNAVAILABLE", "Unable to verify order");
            return mapper.readValue(res.body(), CashQuote.class);
        } catch (PaymentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new PaymentException(503, "ORDER_UNAVAILABLE", "Unable to verify order");
        }
    }

    public CancellationQuote cancellation(UUID orderId, String bearer) {
        try {
            var req = HttpRequest.newBuilder(base.resolve("api/v1/orders/" + orderId))
                    .timeout(Duration.ofSeconds(4)).header("Authorization", bearer).GET().build();
            var res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200)
                throw new PaymentException(503, "ORDER_UNAVAILABLE", "Unable to verify cancellation");
            var order = mapper.readTree(res.body());
            return new CancellationQuote(orderId, UUID.fromString(order.path("branchId").asText()),
                    order.path("totalVnd").asLong(), order.path("status").asText());
        } catch (PaymentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new PaymentException(503, "ORDER_UNAVAILABLE", "Unable to verify cancellation");
        }
    }

    public void confirm(UUID orderId, UUID paymentId, String bearer) {
        try {
            var req = HttpRequest.newBuilder(base.resolve("api/v1/orders/" + orderId + "/confirm-cash"))
                    .timeout(Duration.ofSeconds(4)).header("Authorization", bearer)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(new Confirm(paymentId))))
                    .build();
            var res = http.send(req, HttpResponse.BodyHandlers.discarding());
            if (res.statusCode() != 200 && res.statusCode() != 204)
                throw new PaymentException(503, "ORDER_CONFIRM_PENDING",
                        "Payment recorded; order confirmation pending");
        } catch (PaymentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new PaymentException(503, "ORDER_CONFIRM_PENDING", "Payment recorded; order confirmation pending");
        }
    }

    public void completeRefund(UUID orderId, UUID refundId, String bearer) {
        try {
            var req = HttpRequest.newBuilder(base.resolve("api/v1/orders/" + orderId + "/complete-refund"))
                    .timeout(Duration.ofSeconds(4)).header("Authorization", bearer)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(new RefundConfirm(refundId))))
                    .build();
            var res = http.send(req, HttpResponse.BodyHandlers.discarding());
            if (res.statusCode() != 200)
                throw new PaymentException(503, "ORDER_REFUND_PENDING", "Refund recorded; order update pending");
        } catch (PaymentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new PaymentException(503, "ORDER_REFUND_PENDING", "Refund recorded; order update pending");
        }
    }

    private record Confirm(UUID paymentId) {
    }

    private record RefundConfirm(UUID refundId) {
    }
}
