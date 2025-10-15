package com.Unthinkable.Summarizer.service.queue;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MeetingJobPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;
    private final String routingKey;

    public MeetingJobPublisher(RabbitTemplate rabbitTemplate,
                               @Value("${app.rabbitmq.exchange}") String exchange,
                               @Value("${app.rabbitmq.routing}") String routingKey) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
        this.routingKey = routingKey;
    }

    public void publish(Integer meetingId) {
        // Synchronous send (quick in normal cases)
        rabbitTemplate.convertAndSend(exchange, routingKey, new MeetingJobMessage(meetingId));
    }

    @Async
    public void publishAsync(Integer meetingId) {
        // Fire-and-forget send to avoid blocking request thread if broker is slow or down
        try {
            log.info("Publishing async job for meeting {}", meetingId);
            rabbitTemplate.convertAndSend(exchange, routingKey, new MeetingJobMessage(meetingId));
        } catch (Exception ignored) {
            // Intentionally ignore to not block the API; worker just won't receive this message
        }
    }
}
