package com.Unthinkable.Summarizer.service.queue;

public class MeetingJobMessage {
    private Integer meetingId;

    public MeetingJobMessage() {}

    public MeetingJobMessage(Integer meetingId) {
        this.meetingId = meetingId;
    }

    public Integer getMeetingId() {
        return meetingId;
    }

    public void setMeetingId(Integer meetingId) {
        this.meetingId = meetingId;
    }
}

