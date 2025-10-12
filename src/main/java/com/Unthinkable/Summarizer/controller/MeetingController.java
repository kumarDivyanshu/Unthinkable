package com.Unthinkable.Summarizer.controller;

import com.Unthinkable.Summarizer.controller.dto.MeetingDtos;
import com.Unthinkable.Summarizer.model.ActionItem;
import com.Unthinkable.Summarizer.model.Meeting;
import com.Unthinkable.Summarizer.repository.ActionItemRepository;
import com.Unthinkable.Summarizer.repository.MeetingRepository;
import com.Unthinkable.Summarizer.repository.SummaryRepository;
import com.Unthinkable.Summarizer.repository.TranscriptRepository;
import com.Unthinkable.Summarizer.service.CurrentUserService;
import com.Unthinkable.Summarizer.service.MeetingProcessingService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
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

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<MeetingDtos.UploadResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title
    ) throws Exception {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        var user = currentUserService.requireCurrentUser();
        var result = meetingProcessingService.processUpload(user.getUserId(), title, file);
        var meeting = meetingRepository.findById(result.meetingId()).orElseThrow();
        return ResponseEntity.ok(new MeetingDtos.UploadResponse(meeting.getMeetingId(), meeting.getStatus()));
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

    @PostMapping("/{id}/reprocess")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<MeetingDtos.Detail> reprocess(@PathVariable("id") Integer id) throws Exception {
        var user = currentUserService.requireCurrentUser();
        var meetingOpt = meetingRepository.findById(id);
        if (meetingOpt.isEmpty() || !meetingOpt.get().getUserId().equals(user.getUserId())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        meetingProcessingService.reprocessMeeting(id);
        // Return fresh details
        var meeting = meetingRepository.findById(id).orElseThrow();
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
