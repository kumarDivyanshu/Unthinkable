package com.Unthinkable.Summarizer.repository;

import com.Unthinkable.Summarizer.model.Meeting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MeetingRepository extends JpaRepository<Meeting, Integer> {
    List<Meeting> findByUserIdOrderByCreatedAtDesc(Integer userId);
}

