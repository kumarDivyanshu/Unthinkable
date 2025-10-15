package com.Unthinkable.Summarizer.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminConfigController {

    private final Environment env;

    public AdminConfigController(Environment env) {
        this.env = env;
    }

    @Value("${app.processing.async:true}")
    private boolean asyncProcessing;

    @GetMapping("/config")
    public Map<String, Object> config() {
        String rabbitHost = env.getProperty("spring.rabbitmq.host", "localhost");
        String rabbitPort = env.getProperty("spring.rabbitmq.port", "5673");
        String queueName  = env.getProperty("app.rabbitmq.queue", "meeting.jobs");
        String ffmpegPath = env.getProperty("app.ffmpeg.path", "ffmpeg");
        String gcpCredsProp   = env.getProperty("app.gcp.credentials-path", "");
        String gcpCredsEnvRaw = System.getenv("GCP_CREDENTIALS_PATH");
        String gacEnvRaw      = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
        String gcpCredsEnv    = gcpCredsEnvRaw == null ? "" : gcpCredsEnvRaw;
        String gacEnv         = gacEnvRaw == null ? "" : gacEnvRaw;
        String resolvedCreds  = resolveCredsPath(gcpCredsProp, gcpCredsEnv, gacEnv);
        boolean resolvedExists = resolvedCreds != null && !resolvedCreds.isBlank() && Files.exists(Path.of(resolvedCreds));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asyncProcessing", asyncProcessing);
        out.put("rabbitHost", rabbitHost);
        out.put("rabbitPort", rabbitPort);
        out.put("queue", queueName);
        out.put("ffmpegPath", ffmpegPath);
        out.put("gcpCredentialsPath", gcpCredsProp == null ? "" : gcpCredsProp);
        out.put("gcpCredentialsPathResolved", resolvedCreds == null ? "" : resolvedCreds);
        out.put("gcpCredentialsExists", resolvedExists);
        out.put("gcpCredentialsEnv", gcpCredsEnv);
        out.put("googleApplicationCredentials", gacEnv);
        return out;
    }

    private String resolveCredsPath(String prop, String gcpCredsEnv, String gacEnv) {
        try {
            if (prop != null && !prop.isBlank() && Files.exists(Path.of(prop))) return prop;
            if (gcpCredsEnv != null && !gcpCredsEnv.isBlank() && Files.exists(Path.of(gcpCredsEnv))) return gcpCredsEnv;
            if (gacEnv != null && !gacEnv.isBlank() && Files.exists(Path.of(gacEnv))) return gacEnv;
            Path wd = Path.of(System.getProperty("user.dir"));
            Path[] candidates = new Path[] {
                    wd.resolve("cred.json"),
                    wd.resolve("Unthinkable").resolve("cred.json"),
                    Path.of("C:/Users/divya/OneDrive/Desktop/summarizeProject/Unthinkable/cred.json")
            };
            for (Path c : candidates) if (Files.exists(c)) return c.toAbsolutePath().toString();
        } catch (Exception ignore) {}
        return "";
    }
}
