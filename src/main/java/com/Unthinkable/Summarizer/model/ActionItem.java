package com.Unthinkable.Summarizer.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "action_items")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ActionItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer actionId;

    @Column(nullable = false)
    private Integer meetingId;

    @Column(nullable = false, length = 1024)
    private String description;

    @Column(length = 255)
    private String assignedTo;

    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ActionStatus status = ActionStatus.PENDING;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public enum ActionStatus {
        PENDING, COMPLETED
    }

    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.status == null) {
            this.status = ActionStatus.PENDING;
        }
    }
}
