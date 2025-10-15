package com.Unthinkable.Summarizer.service.queue;

import com.Unthinkable.Summarizer.service.MeetingProcessingService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import com.Unthinkable.Summarizer.service.queue.MeetingJobMessage;

@Component
@RequiredArgsConstructor
public class MeetingJobWorker {

    private static final Logger log = LoggerFactory.getLogger(MeetingJobWorker.class);

    private final MeetingProcessingService meetingProcessingService;

    @RabbitListener(queues = "${app.rabbitmq.queue}")
    public void handle(MeetingJobMessage msg) {
        if (msg == null || msg.getMeetingId() == null) {
            log.warn("Received invalid message: {}", msg);
            return;
        }
        Integer meetingId = msg.getMeetingId();
        log.info("Worker: processing meeting {}", meetingId);
        try {
            meetingProcessingService.reprocessMeeting(meetingId);
            log.info("Worker: meeting {} completed", meetingId);
        } catch (Exception e) {
            log.error("Worker: meeting {} failed: {}", meetingId, e.toString(), e);
            // persist FAILED status outside rolled back transaction
            meetingProcessingService.markFailed(meetingId);
        }
    }
}
