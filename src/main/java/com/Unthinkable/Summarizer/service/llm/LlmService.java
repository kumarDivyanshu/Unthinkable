package com.Unthinkable.Summarizer.service.llm;

public interface LlmService {
    SummaryResult summarize(String transcript) throws Exception;
}

