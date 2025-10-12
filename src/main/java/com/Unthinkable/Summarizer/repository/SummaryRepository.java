package com.Unthinkable.Summarizer.repository;

import com.Unthinkable.Summarizer.model.Summary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SummaryRepository extends JpaRepository<Summary, Integer> {
    Optional<Summary> findByMeetingId(Integer meetingId);
}

