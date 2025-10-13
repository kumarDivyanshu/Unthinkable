package com.Unthinkable.Summarizer.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
public class StorageService {

    private final Path baseDir;

    public StorageService(@Value("${app.storage.base-dir:./uploads}") String baseDir) throws IOException {
        this.baseDir = Path.of(baseDir).toAbsolutePath().normalize();
        Files.createDirectories(this.baseDir);
    }

    public Path saveAudio(Integer userId, MultipartFile file) throws IOException {
        String original = StringUtils.cleanPath(file.getOriginalFilename() == null ? "audio" : file.getOriginalFilename());
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String ext = original.contains(".") ? original.substring(original.lastIndexOf('.')) : ".wav";
        String name = ts + "-" + UUID.randomUUID() + ext;
        Path userDir = baseDir.resolve("user-" + userId);
        Files.createDirectories(userDir);
        Path out = userDir.resolve(name);
        Files.copy(file.getInputStream(), out, StandardCopyOption.REPLACE_EXISTING);
        return out;
    }
}

