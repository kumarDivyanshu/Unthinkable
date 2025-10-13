package com.Unthinkable.Summarizer.service.openai;

import com.Unthinkable.Summarizer.service.llm.SummaryResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class OpenAiClient {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.openai.api-key:}")
    private String apiKey;

    @Value("${app.openai.chat.model:gpt-4o-mini}")
    private String chatModel;

    @Value("${app.openai.asr.model:whisper-1}")
    private String asrModel;

    private HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String transcribe(Path audio) throws Exception {
        if (!isConfigured()) throw new IllegalStateException("OpenAI API key not configured");
        String boundary = "Boundary-" + UUID.randomUUID();
        String CRLF = "\r\n";
        byte[] fileBytes = Files.readAllBytes(audio);
        String fileName = audio.getFileName().toString();

        StringBuilder sb = new StringBuilder();
        // model field
        sb.append("--").append(boundary).append(CRLF);
        sb.append("Content-Disposition: form-data; name=\"model\"").append(CRLF).append(CRLF);
        sb.append(asrModel).append(CRLF);
        // response_format json
        sb.append("--").append(boundary).append(CRLF);
        sb.append("Content-Disposition: form-data; name=\"response_format\"").append(CRLF).append(CRLF);
        sb.append("json").append(CRLF);
        // file part header
        sb.append("--").append(boundary).append(CRLF);
        sb.append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(fileName).append("\"").append(CRLF);
        sb.append("Content-Type: application/octet-stream").append(CRLF).append(CRLF);
        byte[] preamble = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] closing = (CRLF + "--" + boundary + "--" + CRLF).getBytes(StandardCharsets.UTF_8);

        byte[] body = new byte[preamble.length + fileBytes.length + closing.length];
        System.arraycopy(preamble, 0, body, 0, preamble.length);
        System.arraycopy(fileBytes, 0, body, preamble.length, fileBytes.length);
        System.arraycopy(closing, 0, body, preamble.length + fileBytes.length, closing.length);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/audio/transcriptions"))
                .timeout(Duration.ofMinutes(2))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IOException("OpenAI ASR failed: " + response.statusCode() + " - " + response.body());
        }
        JsonNode node = objectMapper.readTree(response.body());
        JsonNode textNode = node.get("text");
        return textNode != null ? textNode.asText() : response.body();
    }

    public SummaryResult summarize(String transcript) throws Exception {
        if (!isConfigured()) throw new IllegalStateException("OpenAI API key not configured");
        String systemPrompt = "You are an expert meeting assistant. Return a strict JSON object with keys: summaryText (string), keyDecisions (string), actionItems (array of objects with description, assignedTo, dueDate in YYYY-MM-DD or null).";
        String userPrompt = "Summarize this meeting transcript into key decisions and action items. Transcript:\n\n" + transcript;

        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", chatModel);
        ArrayNode messages = root.putArray("messages");
        ObjectNode systemMsg = objectMapper.createObjectNode();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        messages.add(systemMsg);
        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);
        messages.add(userMsg);
        ObjectNode responseFormat = objectMapper.createObjectNode();
        responseFormat.put("type", "json_object");
        root.set("response_format", responseFormat);

        String body = objectMapper.writeValueAsString(root);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IOException("OpenAI Chat failed: " + response.statusCode() + " - " + response.body());
        }
        JsonNode rootResponse = objectMapper.readTree(response.body());
        String content = rootResponse.path("choices").path(0).path("message").path("content").asText();
        JsonNode json;
        try {
            json = objectMapper.readTree(content);
        } catch (Exception e) {
            String stripped = content.replaceAll("(?s)```json|```", "").trim();
            json = objectMapper.readTree(stripped);
        }
        SummaryResult result = new SummaryResult();
        result.setSummaryText(json.path("summaryText").asText(""));
        result.setKeyDecisions(json.path("keyDecisions").asText(""));
        List<SummaryResult.ActionItemSuggestion> items = new ArrayList<>();
        for (JsonNode it : json.path("actionItems")) {
            SummaryResult.ActionItemSuggestion s = new SummaryResult.ActionItemSuggestion();
            s.setDescription(it.path("description").asText(""));
            s.setAssignedTo(it.path("assignedTo").asText(null));
            String due = it.path("dueDate").asText(null);
            if (due != null && !due.isBlank() && due.matches("\\d{4}-\\d{2}-\\d{2}")) {
                s.setDueDate(java.time.LocalDate.parse(due));
            }
            items.add(s);
        }
        result.setActionItems(items);
        return result;
    }
}
