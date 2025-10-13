package com.Unthinkable.Summarizer.service.gemini;

import com.Unthinkable.Summarizer.service.llm.SummaryResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Component
public class GeminiClient {

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${app.gemini.api-key:}")
    private String apiKey;

    @Value("${app.gemini.model:gemini-2.0-flash}")
    private String model;

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String transcribe(Path audioPath) throws Exception {
        if (!isConfigured()) throw new IllegalStateException("Gemini API key not configured");
        byte[] audioBytes = Files.readAllBytes(audioPath);
        String b64 = Base64.getEncoder().encodeToString(audioBytes);
        String mime = guessMime(audioPath.getFileName().toString());

        ObjectNode root = mapper.createObjectNode();
        ArrayNode contents = root.putArray("contents");
        ObjectNode user = mapper.createObjectNode();
        user.put("role", "user");
        ArrayNode parts = user.putArray("parts");
        parts.add(mapper.createObjectNode().put("text", "Transcribe this audio. Return only the transcript text."));
        ObjectNode inline = mapper.createObjectNode();
        inline.put("mime_type", mime);
        inline.put("data", b64);
        parts.add(mapper.createObjectNode().set("inline_data", inline));
        contents.add(user);

        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(root)))
                .build();
        HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) {
            throw new IllegalStateException("Gemini ASR failed: " + resp.statusCode() + " - " + resp.body());
        }
        JsonNode node = mapper.readTree(resp.body());
        String text = node.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
        return text;
    }

    private String guessMime(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".m4a")) return "audio/mp4";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".webm")) return "audio/webm";
        return "application/octet-stream";
    }

    public SummaryResult summarize(String transcript) throws Exception {
        if (!isConfigured()) throw new IllegalStateException("Gemini API key not configured");
        String systemPrompt = "You are an expert meeting assistant. Return a strict JSON object with keys: summaryText (string), keyDecisions (string), actionItems (array of objects with description, assignedTo, dueDate in YYYY-MM-DD or null).";
        ObjectNode root = mapper.createObjectNode();
        ArrayNode contents = root.putArray("contents");
        ObjectNode user = mapper.createObjectNode();
        user.put("role", "user");
        ArrayNode parts = user.putArray("parts");
        parts.add(mapper.createObjectNode().put("text", systemPrompt + "\n\nSummarize this meeting transcript into key decisions and action items. Transcript:\n\n" + transcript));
        contents.add(user);
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(root)))
                .build();
        HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) {
            throw new IllegalStateException("Gemini summarize failed: " + resp.statusCode() + " - " + resp.body());
        }
        String content = mapper.readTree(resp.body())
                .path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
        JsonNode json;
        try {
            json = mapper.readTree(content);
        } catch (Exception e) {
            String stripped = content.replaceAll("(?s)```json|```", "").trim();
            json = mapper.readTree(stripped);
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
