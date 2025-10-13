package com.Unthinkable.Summarizer.service.asr;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Service
@Primary
@RequiredArgsConstructor
public class AsrFacade implements AsrService {

    private final GcpAsrService gcpAsrService;

    @Override
    public String transcribe(Path audioFile) throws Exception {
        return gcpAsrService.transcribe(audioFile);
    }
}
