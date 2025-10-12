package com.Unthinkable.Summarizer;

import com.Unthinkable.Summarizer.model.Meeting;
import com.Unthinkable.Summarizer.model.User;
import com.Unthinkable.Summarizer.repository.*;
import com.Unthinkable.Summarizer.service.MeetingProcessingService;
import com.Unthinkable.Summarizer.service.openai.OpenAiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class MeetingProcessingServiceTest {

    @Autowired
    MeetingProcessingService service;
    @Autowired
    UserRepository userRepository;
    @Autowired
    MeetingRepository meetingRepository;
    @Autowired
    TranscriptRepository transcriptRepository;
    @Autowired
    SummaryRepository summaryRepository;
    @Autowired
    ActionItemRepository actionItemRepository;
    @Autowired
    OpenAiClient openAiClient;

    @Test
    @Transactional
    void processUpload_behaviorDependsOnKey() throws Exception {
        // Arrange user
        User u = new User();
        u.setEmail("test@example.com");
        u.setFullName("Test User");
        u.setPasswordHash("noop");
        u = userRepository.save(u);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "meeting.wav",
                "audio/wav",
                new byte[]{0,1,2,3,4,5}
        );

        if (openAiClient.isConfigured()) {
            // Act
            var result = service.processUpload(u.getUserId(), "Test Meeting", file);

            // Assert meeting exists and completed
            var meeting = meetingRepository.findById(result.meetingId()).orElseThrow();
            assertEquals(Meeting.MeetingStatus.COMPLETED, meeting.getStatus());
            assertNotNull(meeting.getAudioFilePath());

            var transcript = transcriptRepository.findByMeetingId(meeting.getMeetingId()).orElse(null);
            assertNotNull(transcript);
            assertNotNull(transcript.getTranscriptText());

            var summary = summaryRepository.findByMeetingId(meeting.getMeetingId()).orElse(null);
            assertNotNull(summary);
            assertNotNull(summary.getSummaryText());

            var actions = actionItemRepository.findByMeetingIdOrderByCreatedAtAsc(meeting.getMeetingId());
            assertNotNull(actions);
            assertFalse(actions.isEmpty());
        } else {
            final Integer userId = u.getUserId();
            final MockMultipartFile uploadFile = file;
            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.processUpload(userId, "Test Meeting", uploadFile));
            assertTrue(ex.getMessage().toLowerCase().contains("api key"));
        }
    }
}
