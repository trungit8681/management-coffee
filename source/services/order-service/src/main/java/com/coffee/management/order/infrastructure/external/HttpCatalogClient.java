package com.coffee.management.order.infrastructure.external;

import com.coffee.management.order.application.OrderException;
import com.coffee.management.order.application.port.CatalogPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class HttpCatalogClient implements CatalogPort {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper mapper;
    private final URI base;

    public HttpCatalogClient(ObjectMapper mapper, @Value("${order.catalog-base-url}") String base) {
        this.mapper = mapper;
        this.base = URI.create(base.endsWith("/") ? base : base + "/");
    }

    public Sellable sellable(UUID variantId, UUID branchId, String channel, String bearer) {
        try {
            var uri = base.resolve(
                    "api/v1/catalog/variants/" + variantId + "/sellable?branchId=" + branchId + "&channel=" + channel);
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(4)).header("Authorization", bearer)
                    .GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404)
                throw new OrderException(409, "ITEM_NOT_SELLABLE", "Item unavailable");
            if (response.statusCode() != 200)
                throw new OrderException(503, "CATALOG_UNAVAILABLE", "Cannot verify menu price");
            return mapper.readValue(response.body(), Sellable.class);
        } catch (OrderException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new OrderException(503, "CATALOG_UNAVAILABLE", "Cannot verify menu price");
        }
    }
}
