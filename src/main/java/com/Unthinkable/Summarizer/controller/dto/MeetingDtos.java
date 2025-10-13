package com.Unthinkable.Summarizer.controller.dto;

import com.Unthinkable.Summarizer.model.ActionItem;
import com.Unthinkable.Summarizer.model.Meeting;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public class MeetingDtos {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UploadResponse {
        private Integer meetingId;
        private Meeting.MeetingStatus status;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ListItem {
        private Integer meetingId;
        private String title;
        private Meeting.MeetingStatus status;
        private LocalDateTime createdAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Detail {
        private Integer meetingId;
        private String title;
        private Meeting.MeetingStatus status;
        private LocalDateTime createdAt;
        private String transcriptText;
        private String summaryText;
        private String keyDecisions;
        private List<ActionItemDTO> actionItems;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActionItemDTO {
        private Integer actionId;
        private String description;
        private String assignedTo;
        private LocalDate dueDate;
        private ActionItem.ActionStatus status;
    }
}

