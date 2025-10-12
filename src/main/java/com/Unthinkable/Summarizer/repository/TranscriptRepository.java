package com.Unthinkable.Summarizer.repository;

import com.Unthinkable.Summarizer.model.Transcript;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TranscriptRepository extends JpaRepository<Transcript, Integer> {
    Optional<Transcript> findByMeetingId(Integer meetingId);
}

