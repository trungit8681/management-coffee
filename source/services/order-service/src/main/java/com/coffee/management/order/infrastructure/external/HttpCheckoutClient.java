package com.coffee.management.order.infrastructure.external;

import com.coffee.management.order.application.OrderException;
import com.coffee.management.order.application.port.CheckoutPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class HttpCheckoutClient implements CheckoutPort {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json;
    private final URI inventory, promotion, loyalty, payment;

    public HttpCheckoutClient(ObjectMapper json,
            @Value("${order.inventory-base-url}") String inventory,
            @Value("${order.promotion-base-url}") String promotion,
            @Value("${order.loyalty-base-url}") String loyalty,
            @Value("${order.payment-base-url}") String payment) {
        this.json=json; this.inventory=uri(inventory); this.promotion=uri(promotion);
        this.loyalty=uri(loyalty); this.payment=uri(payment);
    }
    private static URI uri(String value) { return URI.create(value.endsWith("/") ? value : value+"/"); }
    private JsonNode post(URI base, String path, Object body, String key, String bearer) {
        try {
            var builder=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(5))
                    .header("Authorization",bearer).header("Content-Type","application/json");
            if (key != null) builder.header("Idempotency-Key",key);
            var response=http.send(builder.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
            if (response.statusCode()<200 || response.statusCode()>=300)
                throw new OrderException(response.statusCode()==409?409:503,"CHECKOUT_STEP_FAILED","Checkout dependency rejected step: "+path);
            return response.body().isBlank()?json.createObjectNode():json.readTree(response.body());
        } catch (OrderException ex) { throw ex; }
        catch (Exception ex) { throw new OrderException(503,"CHECKOUT_DEPENDENCY_UNAVAILABLE","Checkout dependency unavailable: "+path); }
    }
    public Benefit reserveVoucher(UUID orderId,UUID branchId,String code,long total,String bearer) {
        var n=post(promotion,"api/v1/promotions/reservations",Map.of("orderId",orderId,"branchId",branchId,"code",code,"totalVnd",total),null,bearer);
        return new Benefit(UUID.fromString(n.get("id").asText()),n.get("discountVnd").asLong(),n.get("status").asText());
    }
    public void finishVoucher(UUID orderId,boolean commit,String bearer) { post(promotion,"api/v1/promotions/reservations/"+orderId+(commit?"/commit":"/release"),Map.of(),null,bearer); }
    public Benefit reservePoints(UUID orderId,UUID customerId,long points,String bearer) {
        var n=post(loyalty,"api/v1/loyalty/reservations",Map.of("orderId",orderId,"customerId",customerId,"points",points),null,bearer);
        return new Benefit(UUID.fromString(n.get("id").asText()),points,n.get("status").asText());
    }
    public void finishPoints(UUID orderId,boolean commit,String bearer) { post(loyalty,"api/v1/loyalty/reservations/"+orderId+(commit?"/commit":"/release"),Map.of(),null,bearer); }
    private UUID stock(String path,UUID orderId,UUID branchId,UUID ingredientId,long quantity,String key,String bearer) {
        var n=post(inventory,path,Map.of("referenceId",orderId,"branchId",branchId,"ingredientId",ingredientId,"quantity",quantity),key,bearer);
        return UUID.fromString(n.get("id").asText());
    }
    public UUID deduct(UUID o,UUID b,UUID i,long q,String k,String bearer){return stock("api/v1/inventory/deductions",o,b,i,q,k,bearer);}
    public UUID reverse(UUID o,UUID b,UUID i,long q,String k,String bearer){return stock("api/v1/inventory/reversals",o,b,i,q,k,bearer);}
    public Payment collect(UUID orderId,long received,String key,String bearer) {
        var n=post(payment,"api/v1/payments/cash",Map.of("orderId",orderId,"cashReceivedVnd",received),key,bearer);
        return new Payment(UUID.fromString(n.get("receipt").get("id").asText()),n.get("orderConfirmed").asBoolean());
    }
}
