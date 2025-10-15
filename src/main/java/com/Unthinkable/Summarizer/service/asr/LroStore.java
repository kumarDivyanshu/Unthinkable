package com.Unthinkable.Summarizer.service.asr;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LroStore {
    private static final Logger log = LoggerFactory.getLogger(LroStore.class);

    private final Object lock = new Object();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path filePath;

    public LroStore(@Value("${app.storage.base-dir:./build/asr-tmp}") String baseDir) throws IOException {
        Path dir = Path.of(baseDir).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        this.filePath = dir.resolve("lro-jobs.json");
        if (!Files.exists(this.filePath)) {
            save(new LinkedHashMap<>());
        }
    }

    public void put(Integer meetingId, String operationName, String gcsUri) {
        synchronized (lock) {
            Map<Integer, Entry> all = load();
            all.put(meetingId, new Entry(operationName, gcsUri));
            save(all);
        }
    }

    public void remove(Integer meetingId) {
        synchronized (lock) {
            Map<Integer, Entry> all = load();
            all.remove(meetingId);
            save(all);
        }
    }

    public Map<Integer, Entry> getAll() {
        synchronized (lock) {
            return Collections.unmodifiableMap(load());
        }
    }

    private Map<Integer, Entry> load() {
        try {
            if (!Files.exists(filePath)) return new LinkedHashMap<>();
            byte[] bytes = Files.readAllBytes(filePath);
            if (bytes.length == 0) return new LinkedHashMap<>();
            return mapper.readValue(bytes, new TypeReference<Map<Integer, Entry>>(){});
        } catch (Exception e) {
            log.warn("Failed to load LRO store: {}", e.toString());
            return new LinkedHashMap<>();
        }
    }

    private void save(Map<Integer, Entry> data) {
        try {
            byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(data);
            Files.write(filePath, bytes);
        } catch (IOException e) {
            log.error("Failed to persist LRO store: {}", e.toString());
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Entry {
        private String operationName;
        private String gcsUri;
    }
}

