package com.Unthinkable.Summarizer.service.llm;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SummaryResult {
    private String summaryText;
    private String keyDecisions;
    private List<ActionItemSuggestion> actionItems = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActionItemSuggestion {
        private String description;
        private String assignedTo; // optional
        private LocalDate dueDate; // optional
    }
}

