package com.Unthinkable.Summarizer.service.asr;

import java.nio.file.Path;

public interface AsrService {
    String transcribe(Path audioFile) throws Exception;
}

