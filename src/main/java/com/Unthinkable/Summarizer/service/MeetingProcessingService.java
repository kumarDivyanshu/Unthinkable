package com.Unthinkable.Summarizer.service;

import com.Unthinkable.Summarizer.model.ActionItem;
import com.Unthinkable.Summarizer.model.Meeting;
import com.Unthinkable.Summarizer.model.Summary;
import com.Unthinkable.Summarizer.model.Transcript;
import com.Unthinkable.Summarizer.repository.ActionItemRepository;
import com.Unthinkable.Summarizer.repository.MeetingRepository;
import com.Unthinkable.Summarizer.repository.SummaryRepository;
import com.Unthinkable.Summarizer.repository.TranscriptRepository;
import com.Unthinkable.Summarizer.repository.UserRepository;
import com.Unthinkable.Summarizer.service.asr.AsrService;
import com.Unthinkable.Summarizer.service.llm.LlmService;
import com.Unthinkable.Summarizer.service.llm.SummaryResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
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
    private final UserRepository userRepository;
    private final MailService mailService;
    private final MeetingTxService meetingTxService;

    // Removed @Transactional so failures don't roll back meeting row creation
    public ProcessResult processUpload(Integer userId, String title, MultipartFile audioFile) throws Exception {
        Meeting meeting = meetingTxService.createProcessingMeeting(userId, title);
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

            // Email user (best-effort)
            try {
                var user = userRepository.findById(userId).orElse(null);
                mailService.sendMeetingSummary(user, meeting, transcript, summary);
            } catch (Exception ignore) {}

            return new ProcessResult(meeting.getMeetingId());
        } catch (Exception ex) {
            markFailed(meeting.getMeetingId());
            throw ex;
        }
    }

    // Avoid holding a DB transaction across file IO
    public ProcessResult createUploadJob(Integer userId, String title, MultipartFile audioFile) throws Exception {
        // 1) Save file to disk first (can take long; no DB connection held)
        Path saved = storageService.saveAudio(userId, audioFile);
        // 2) Create meeting quickly in its own short transaction and persist path
        Meeting meeting = meetingTxService.createProcessingMeeting(userId, title);
        meeting.setAudioFilePath(saved.toString());
        meetingRepository.save(meeting);
        return new ProcessResult(meeting.getMeetingId());
    }

    // Avoid holding a DB transaction across file IO
    public ProcessResult createUploadJobFromPath(Integer userId, String title, Path savedAudioPath) {
        Meeting meeting = meetingTxService.createProcessingMeeting(userId, title);
        meeting.setAudioFilePath(savedAudioPath.toString());
        meetingRepository.save(meeting);
        return new ProcessResult(meeting.getMeetingId());
    }

    // Do not annotate the whole method as transactional to prevent long-running external calls
    // from keeping a DB connection checked out. Repository operations are transactional by default.
    public ProcessResult reprocessMeeting(Integer meetingId) throws Exception {
        Meeting meeting = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new IllegalArgumentException("Meeting not found"));
        if (meeting.getAudioFilePath() == null || meeting.getAudioFilePath().isBlank()) {
            throw new IllegalStateException("No audio file path stored for this meeting");
        }
        // mark as processing quickly
        meeting.setStatus(Meeting.MeetingStatus.PROCESSING);
        meetingRepository.save(meeting);
        try {
            Path audioPath = Path.of(meeting.getAudioFilePath());
            String transcriptText = asrService.transcribe(audioPath);

            Transcript transcript = transcriptRepository.findByMeetingId(meetingId).orElseGet(Transcript::new);
            transcript.setMeetingId(meetingId);
            transcript.setTranscriptText(transcriptText);
            transcriptRepository.save(transcript);

            SummaryResult summaryResult = llmService.summarize(transcriptText);
            Summary summary = summaryRepository.findByMeetingId(meetingId).orElseGet(Summary::new);
            summary.setMeetingId(meetingId);
            summary.setSummaryText(summaryResult.getSummaryText());
            summary.setKeyDecisions(summaryResult.getKeyDecisions());
            summaryRepository.save(summary);

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

            try {
                var user = userRepository.findById(meeting.getUserId()).orElse(null);
                mailService.sendMeetingSummary(user, meeting, transcript, summary);
            } catch (Exception ignore) {}

            return new ProcessResult(meetingId);
        } catch (Exception ex) {
            markFailed(meetingId);
            throw ex;
        }
    }

    public void markFailed(Integer meetingId) {
        try {
            meetingTxService.markFailed(meetingId);
        } catch (Exception ignore) {}
    }

    public record ProcessResult(Integer meetingId) {}
}
