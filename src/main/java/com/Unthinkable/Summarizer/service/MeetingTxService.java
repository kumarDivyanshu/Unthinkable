package com.Unthinkable.Summarizer.service;

import com.Unthinkable.Summarizer.model.Meeting;
import com.Unthinkable.Summarizer.repository.MeetingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MeetingTxService {

    private final MeetingRepository meetingRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Meeting createProcessingMeeting(Integer userId, String title) {
        Meeting meeting = new Meeting();
        meeting.setUserId(userId);
        meeting.setTitle(title == null || title.isBlank() ? "Meeting" : title.trim());
        meeting.setStatus(Meeting.MeetingStatus.PROCESSING);
        return meetingRepository.save(meeting);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Integer meetingId) {
        Meeting m = meetingRepository.findById(meetingId).orElse(null);
        if (m != null) {
            m.setStatus(Meeting.MeetingStatus.FAILED);
            meetingRepository.save(m);
        }
    }
}

