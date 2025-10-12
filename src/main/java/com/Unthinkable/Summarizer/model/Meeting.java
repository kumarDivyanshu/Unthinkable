package com.Unthinkable.Summarizer.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "meetings")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Meeting {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer meetingId;

    @Column(nullable = false)
    private Integer userId;

    @Column(nullable = false, length = 255)
    private String title;

    private LocalDateTime meetingDate;

    @Column(length = 1024)
    private String audioFilePath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MeetingStatus status = MeetingStatus.UPLOADED;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public enum MeetingStatus {
        UPLOADED, PROCESSING, COMPLETED, FAILED
    }

    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.status == null) {
            this.status = MeetingStatus.UPLOADED;
        }
    }
}
