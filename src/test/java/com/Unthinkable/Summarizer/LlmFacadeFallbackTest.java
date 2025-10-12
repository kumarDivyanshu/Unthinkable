package com.Unthinkable.Summarizer;

import com.Unthinkable.Summarizer.service.llm.LlmService;
import com.Unthinkable.Summarizer.service.llm.SummaryResult;
import com.Unthinkable.Summarizer.service.openai.OpenAiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class LlmFacadeFallbackTest {

    @Autowired
    LlmService llmService;
    @Autowired
    OpenAiClient openAiClient;

    @Test
    void summarizeBehaviorDependsOnKey() throws Exception {
        String transcript = "Alice: Let's plan the release. Bob: Ship on Friday. Charlie: I'll write docs.";
        if (openAiClient.isConfigured()) {
            SummaryResult r = llmService.summarize(transcript);
            assertNotNull(r);
            assertNotNull(r.getSummaryText());
        } else {
            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> llmService.summarize(transcript));
            assertTrue(ex.getMessage().toLowerCase().contains("api key"));
        }
    }
}
