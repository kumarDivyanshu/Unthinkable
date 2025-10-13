package com.Unthinkable.Summarizer.service.llm;

import com.Unthinkable.Summarizer.service.gemini.GeminiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LlmFacade implements LlmService {

    private final GeminiClient geminiClient;

    @Value("${app.llm.provider:gemini}")
    private String provider;

    @Override
    public SummaryResult summarize(String transcript) throws Exception {
        return switch (provider.toLowerCase()) {
            case "gemini" -> geminiClient.summarize(transcript);
            default -> throw new IllegalStateException("Unknown LLM provider: " + provider + ". Use 'gemini' or 'ollama'.");
        };
    }
}
