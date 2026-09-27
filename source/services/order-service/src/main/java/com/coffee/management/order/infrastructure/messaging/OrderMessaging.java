package com.coffee.management.order.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Configuration
@ConditionalOnProperty(name="order.messaging.enabled",havingValue="true")
class OrderMessagingConfiguration {
    @Bean Declarables orderQueues() {
        var exchange=new TopicExchange("coffee.events",true,false);
        var dlx=new DirectExchange("coffee.events.dlx",true,false);
        var queue=QueueBuilder.durable("order.cash-payment-recorded.v1")
                .withArgument("x-dead-letter-exchange",dlx.getName())
                .withArgument("x-dead-letter-routing-key","order.cash-payment-recorded.v1.dlq").build();
        var dlq=QueueBuilder.durable("order.cash-payment-recorded.v1.dlq").build();
        return new Declarables(exchange,dlx,queue,dlq,
                BindingBuilder.bind(queue).to(exchange).with("CashPaymentRecorded.v1"),
                BindingBuilder.bind(dlq).to(dlx).with("order.cash-payment-recorded.v1.dlq"));
    }
}

@Component
@ConditionalOnProperty(name="order.messaging.enabled",havingValue="true")
class CashPaymentInboxConsumer {
    private final JdbcTemplate db; private final ObjectMapper json;
    CashPaymentInboxConsumer(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}

    @RabbitListener(queues="order.cash-payment-recorded.v1")
    @Transactional
    public void receive(String body) throws Exception {
        JsonNode envelope=json.readTree(body);
        UUID eventId=UUID.fromString(envelope.path("eventId").asText());
        int inserted=db.update("INSERT INTO inbox_event(event_id,event_type,source,payload) VALUES (?,?,?,CAST(? AS jsonb)) ON CONFLICT DO NOTHING",
                eventId,envelope.path("eventType").asText(),envelope.path("source").asText(),json.writeValueAsString(envelope.path("payload")));
        if(inserted==0) return;
        JsonNode payload=envelope.path("payload");
        UUID orderId=UUID.fromString(payload.path("orderId").asText());
        UUID paymentId=UUID.fromString(payload.path("paymentId").asText());
        db.update("UPDATE checkout_saga SET status='PAID_PENDING',payment_id=?,updated_at=now() WHERE order_id=? AND status NOT IN ('COMPLETED','FAILED')",paymentId,orderId);
        db.update("UPDATE inbox_event SET processed_at=now() WHERE event_id=?",eventId);
    }
}
