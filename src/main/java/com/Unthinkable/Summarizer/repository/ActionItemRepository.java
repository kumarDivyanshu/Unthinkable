package com.Unthinkable.Summarizer.repository;

import com.Unthinkable.Summarizer.model.ActionItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ActionItemRepository extends JpaRepository<ActionItem, Integer> {
    List<ActionItem> findByMeetingIdOrderByCreatedAtAsc(Integer meetingId);
}

