package com.Unthinkable.Summarizer.controller;

import com.Unthinkable.Summarizer.controller.dto.MeetingDtos;
import com.Unthinkable.Summarizer.model.Meeting;
import com.Unthinkable.Summarizer.repository.ActionItemRepository;
import com.Unthinkable.Summarizer.repository.MeetingRepository;
import com.Unthinkable.Summarizer.repository.SummaryRepository;
import com.Unthinkable.Summarizer.repository.TranscriptRepository;
import com.Unthinkable.Summarizer.service.CurrentUserService;
import com.Unthinkable.Summarizer.service.MeetingProcessingService;
import com.Unthinkable.Summarizer.service.queue.MeetingJobPublisher;
import com.Unthinkable.Summarizer.service.StorageService;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/meetings")
@RequiredArgsConstructor
public class MeetingController {

    private final CurrentUserService currentUserService;
    private final MeetingRepository meetingRepository;
    private final TranscriptRepository transcriptRepository;
    private final SummaryRepository summaryRepository;
    private final ActionItemRepository actionItemRepository;
    private final MeetingProcessingService meetingProcessingService;
    private final MeetingJobPublisher meetingJobPublisher;
    private final StorageService storageService;

    @Value("${app.processing.async:true}")
    private boolean asyncProcessing;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PermitAll
    public ResponseEntity<MeetingDtos.UploadResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title
    ) throws Exception {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        var user = currentUserService.requireCurrentUserOrGuest();

        if (asyncProcessing) {
            var result = meetingProcessingService.createUploadJob(user.getUserId(), title, file);
            // publish for async processing (fire-and-forget)
            meetingJobPublisher.publishAsync(result.meetingId());
            // Avoid extra DB read here; we know status is PROCESSING initially
            return ResponseEntity.ok(new MeetingDtos.UploadResponse(result.meetingId(), Meeting.MeetingStatus.PROCESSING));
        } else {
            var result = meetingProcessingService.processUpload(user.getUserId(), title, file);
            var meeting = meetingRepository.findById(result.meetingId()).orElseThrow();
            return ResponseEntity.ok(new MeetingDtos.UploadResponse(meeting.getMeetingId(), meeting.getStatus()));
        }
    }

    @PostMapping(path = "/raw", consumes = {"audio/*", MediaType.APPLICATION_OCTET_STREAM_VALUE})
    @PermitAll
    public ResponseEntity<MeetingDtos.UploadResponse> uploadRaw(
            HttpServletRequest request,
            @RequestHeader(value = "Content-Type", required = false) String contentType,
            @RequestParam(value = "title", required = false) String title
    ) throws Exception {
        var user = currentUserService.requireCurrentUserOrGuest();
        String ext = switch (contentType == null ? "" : contentType.toLowerCase()) {
            case "audio/mpeg", "audio/mp3" -> ".mp3";
            case "audio/wav", "audio/x-wav", "audio/wave" -> ".wav";
            case "audio/webm" -> ".webm";
            case "audio/ogg" -> ".ogg";
            case "audio/aac" -> ".aac";
            case "audio/flac" -> ".flac";
            default -> ".wav";
        };
        String originalName = "upload" + ext;
        var savedPath = storageService.saveAudioFromStream(user.getUserId(), request.getInputStream(), originalName);
        if (asyncProcessing) {
            var result = meetingProcessingService.createUploadJobFromPath(user.getUserId(), title, savedPath);
            meetingJobPublisher.publishAsync(result.meetingId());
            return ResponseEntity.ok(new MeetingDtos.UploadResponse(result.meetingId(), Meeting.MeetingStatus.PROCESSING));
        } else {
            var result = meetingProcessingService.createUploadJobFromPath(user.getUserId(), title, savedPath);
            meetingProcessingService.reprocessMeeting(result.meetingId());
            var meeting = meetingRepository.findById(result.meetingId()).orElseThrow();
            return ResponseEntity.ok(new MeetingDtos.UploadResponse(meeting.getMeetingId(), meeting.getStatus()));
        }
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<MeetingDtos.ListItem>> list() {
        var user = currentUserService.requireCurrentUser();
        List<MeetingDtos.ListItem> items = meetingRepository.findByUserIdOrderByCreatedAtDesc(user.getUserId())
                .stream()
                .map(m -> new MeetingDtos.ListItem(m.getMeetingId(), m.getTitle(), m.getStatus(), m.getCreatedAt()))
                .toList();
        return ResponseEntity.ok(items);
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<MeetingDtos.Detail> detail(@PathVariable("id") Integer id) {
        var user = currentUserService.requireCurrentUser();
        var meetingOpt = meetingRepository.findById(id);
        if (meetingOpt.isEmpty() || !meetingOpt.get().getUserId().equals(user.getUserId())) {
            return ResponseEntity.notFound().build();
        }
        var meeting = meetingOpt.get();
        return getDetailResponseEntity(id, meeting);
    }

    @PostMapping("/{id}/reprocess")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<MeetingDtos.Detail> reprocess(@PathVariable("id") Integer id) throws Exception {
        var user = currentUserService.requireCurrentUser();
        var meetingOpt = meetingRepository.findById(id);
        if (meetingOpt.isEmpty() || !meetingOpt.get().getUserId().equals(user.getUserId())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (asyncProcessing) {
            meetingJobPublisher.publishAsync(id);
        } else {
            meetingProcessingService.reprocessMeeting(id);
        }
        // Return fresh details
        var meeting = meetingRepository.findById(id).orElseThrow();
        return getDetailResponseEntity(id, meeting);
    }

    private ResponseEntity<MeetingDtos.Detail> getDetailResponseEntity(Integer id, Meeting meeting) {
        var transcript = transcriptRepository.findByMeetingId(id).orElse(null);
        var summary = summaryRepository.findByMeetingId(id).orElse(null);
        var actions = actionItemRepository.findByMeetingIdOrderByCreatedAtAsc(id).stream()
                .map(ai -> new MeetingDtos.ActionItemDTO(ai.getActionId(), ai.getDescription(), ai.getAssignedTo(), ai.getDueDate(), ai.getStatus()))
                .toList();
        var dto = new MeetingDtos.Detail(
                meeting.getMeetingId(),
                meeting.getTitle(),
                meeting.getStatus(),
                meeting.getCreatedAt(),
                transcript != null ? transcript.getTranscriptText() : null,
                summary != null ? summary.getSummaryText() : null,
                summary != null ? summary.getKeyDecisions() : null,
                actions
        );
        return ResponseEntity.ok(dto);
    }
}
