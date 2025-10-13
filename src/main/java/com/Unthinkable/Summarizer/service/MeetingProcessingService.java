package com.Unthinkable.Summarizer.service;

import com.Unthinkable.Summarizer.model.ActionItem;
import com.Unthinkable.Summarizer.model.Meeting;
import com.Unthinkable.Summarizer.model.Summary;
import com.Unthinkable.Summarizer.model.Transcript;
import com.Unthinkable.Summarizer.repository.ActionItemRepository;
import com.Unthinkable.Summarizer.repository.MeetingRepository;
import com.Unthinkable.Summarizer.repository.SummaryRepository;
import com.Unthinkable.Summarizer.repository.TranscriptRepository;
import com.Unthinkable.Summarizer.service.asr.AsrService;
import com.Unthinkable.Summarizer.service.llm.LlmService;
import com.Unthinkable.Summarizer.service.llm.SummaryResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;

@Service
@RequiredArgsConstructor
public class MeetingProcessingService {

    private final StorageService storageService;
    private final AsrService asrService;
    private final LlmService llmService;
    private final MeetingRepository meetingRepository;
    private final TranscriptRepository transcriptRepository;
    private final SummaryRepository summaryRepository;
    private final ActionItemRepository actionItemRepository;

    @Transactional
    public ProcessResult processUpload(Integer userId, String title, MultipartFile audioFile) throws Exception {
        // Create meeting row as PROCESSING
        Meeting meeting = new Meeting();
        meeting.setUserId(userId);
        meeting.setTitle(title == null || title.isBlank() ? "Meeting" : title.trim());
        meeting.setStatus(Meeting.MeetingStatus.PROCESSING);
        meeting = meetingRepository.save(meeting);

        try {
            // Store audio
            Path saved = storageService.saveAudio(userId, audioFile);
            meeting.setAudioFilePath(saved.toString());
            meetingRepository.save(meeting);

            // Transcribe
            String transcriptText = asrService.transcribe(saved);
            Transcript transcript = new Transcript();
            transcript.setMeetingId(meeting.getMeetingId());
            transcript.setTranscriptText(transcriptText);
            transcriptRepository.save(transcript);

            // Summarize
            SummaryResult summaryResult = llmService.summarize(transcriptText);
            Summary summary = new Summary();
            summary.setMeetingId(meeting.getMeetingId());
            summary.setSummaryText(summaryResult.getSummaryText());
            summary.setKeyDecisions(summaryResult.getKeyDecisions());
            summaryRepository.save(summary);

            // Action items
            for (SummaryResult.ActionItemSuggestion s : summaryResult.getActionItems()) {
                ActionItem ai = new ActionItem();
                ai.setMeetingId(meeting.getMeetingId());
                ai.setDescription(s.getDescription());
                ai.setAssignedTo(s.getAssignedTo());
                ai.setDueDate(s.getDueDate());
                actionItemRepository.save(ai);
            }

            meeting.setStatus(Meeting.MeetingStatus.COMPLETED);
            meetingRepository.save(meeting);

            return new ProcessResult(meeting.getMeetingId());
        } catch (Exception ex) {
            meeting.setStatus(Meeting.MeetingStatus.FAILED);
            meetingRepository.save(meeting);
            throw ex;
        }
    }

    @Transactional
    public ProcessResult reprocessMeeting(Integer meetingId) throws Exception {
        Meeting meeting = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new IllegalArgumentException("Meeting not found"));
        if (meeting.getAudioFilePath() == null || meeting.getAudioFilePath().isBlank()) {
            throw new IllegalStateException("No audio file path stored for this meeting");
        }
        meeting.setStatus(Meeting.MeetingStatus.PROCESSING);
        meetingRepository.save(meeting);
        try {
            Path audioPath = Path.of(meeting.getAudioFilePath());
            String transcriptText = asrService.transcribe(audioPath);

            // Upsert Transcript
            Transcript transcript = transcriptRepository.findByMeetingId(meetingId).orElseGet(Transcript::new);
            transcript.setMeetingId(meetingId);
            transcript.setTranscriptText(transcriptText);
            transcriptRepository.save(transcript);

            // Summarize
            SummaryResult summaryResult = llmService.summarize(transcriptText);
            Summary summary = summaryRepository.findByMeetingId(meetingId).orElseGet(Summary::new);
            summary.setMeetingId(meetingId);
            summary.setSummaryText(summaryResult.getSummaryText());
            summary.setKeyDecisions(summaryResult.getKeyDecisions());
            summaryRepository.save(summary);

            // Replace Action Items
            var existing = actionItemRepository.findByMeetingIdOrderByCreatedAtAsc(meetingId);
            actionItemRepository.deleteAll(existing);
            for (SummaryResult.ActionItemSuggestion s : summaryResult.getActionItems()) {
                ActionItem ai = new ActionItem();
                ai.setMeetingId(meetingId);
                ai.setDescription(s.getDescription());
                ai.setAssignedTo(s.getAssignedTo());
                ai.setDueDate(s.getDueDate());
                actionItemRepository.save(ai);
            }

            meeting.setStatus(Meeting.MeetingStatus.COMPLETED);
            meetingRepository.save(meeting);
            return new ProcessResult(meetingId);
        } catch (Exception ex) {
            meeting.setStatus(Meeting.MeetingStatus.FAILED);
            meetingRepository.save(meeting);
            throw ex;
        }
    }

    public record ProcessResult(Integer meetingId) {}
}
