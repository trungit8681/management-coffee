package com.coffee.management.order.infrastructure.external;

import com.coffee.management.order.application.OrderException;
import com.coffee.management.order.application.port.PaymentPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class HttpPaymentClient implements PaymentPort {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper mapper;
    private final URI base;

    public HttpPaymentClient(ObjectMapper mapper, @Value("${order.payment-base-url}") String base) {
        this.mapper = mapper;
        this.base = URI.create(base.endsWith("/") ? base : base + "/");
    }

    public Receipt receipt(UUID orderId, String bearer) {
        try {
            var request = HttpRequest.newBuilder(base.resolve("api/v1/payments/cash/by-order/" + orderId))
                    .timeout(Duration.ofSeconds(4)).header("Authorization", bearer).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200)
                throw new OrderException(503, "PAYMENT_UNAVAILABLE", "Cannot verify cash receipt");
            return mapper.readValue(response.body(), Receipt.class);
        } catch (OrderException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new OrderException(503, "PAYMENT_UNAVAILABLE", "Cannot verify cash receipt");
        }
    }

    public Refund refund(UUID orderId, String bearer) {
        try {
            var request = HttpRequest.newBuilder(base.resolve("api/v1/payments/cash/refunds/by-order/" + orderId))
                    .timeout(Duration.ofSeconds(4)).header("Authorization", bearer).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200)
                throw new OrderException(503, "PAYMENT_UNAVAILABLE", "Cannot verify cash refund");
            return mapper.readValue(response.body(), Refund.class);
        } catch (OrderException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new OrderException(503, "PAYMENT_UNAVAILABLE", "Cannot verify cash refund");
        }
    }
}
