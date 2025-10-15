package com.Unthinkable.Summarizer.service.asr;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.longrunning.OperationFuture;
import com.google.api.gax.longrunning.OperationTimedPollAlgorithm;
import com.google.api.gax.retrying.RetrySettings;
import com.google.auth.Credentials;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.speech.v1.*;
import com.google.cloud.storage.*;
import com.google.protobuf.ByteString;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;
import org.threeten.bp.Duration;
import com.google.longrunning.Operation;
import com.google.longrunning.OperationsClient;

@Service
public class GcpAsrService implements AsrService {

    private static final Logger log = LoggerFactory.getLogger(GcpAsrService.class);

    @Value("${app.gcp.credentials-path:}")
    private String credentialsPath;

    @Value("${app.gcp.language-code:en-US}")
    private String languageCode;

    @Value("${app.gcp.bucket:}")
    private String bucketName;

    @Value("${app.ffmpeg.path:ffmpeg}")
    private String ffmpegPath;

    // ~8 MB threshold for switching to long-running with GCS
    private static final long SYNC_MAX_BYTES = 8L * 1024L * 1024L;

    @PostConstruct
    void validateConfig() {
        try {
            Path resolved = tryResolveCredentialsPath();
            if (resolved != null && Files.exists(resolved)) {
                log.info("GCP credentials path set: {} (exists)", resolved.toAbsolutePath());
            } else if (credentialsPath != null && !credentialsPath.isBlank()) {
                Path p = Path.of(credentialsPath);
                throw new IllegalStateException("GCP credentials path not found: " + p.toAbsolutePath());
            } else {
                log.info("No explicit credentials file configured. Will attempt GOOGLE_APPLICATION_CREDENTIALS or ADC.");
            }
            if (bucketName == null || bucketName.isBlank()) {
                log.warn("app.gcp.bucket is not set. Large files (> ~8MB) will fail long-running recognition.");
            } else {
                log.info("Using GCS bucket: {}", bucketName);
            }
            // ffmpeg command resolution
            String ffmpegCmd = resolveFfmpegCmd();
            log.info("Using ffmpeg command: {}", ffmpegCmd);
        } catch (Exception e) {
            throw (e instanceof RuntimeException) ? (RuntimeException) e : new RuntimeException(e);
        }
    }

    private String resolveFfmpegCmd() {
        try {
            // If explicit path configured and exists, use it
            if (ffmpegPath != null && !ffmpegPath.isBlank()) {
                Path p = Path.of(ffmpegPath);
                if (Files.exists(p)) return p.toString();
            }
            // Try env vars explicitly if different from property
            String env1 = System.getenv("FFMPEG_PATH");
            if (env1 != null && !env1.isBlank()) {
                Path p = Path.of(env1);
                if (Files.exists(p)) return p.toString();
            }
            String env2 = System.getenv("APP_FFMPEG_PATH");
            if (env2 != null && !env2.isBlank()) {
                Path p = Path.of(env2);
                if (Files.exists(p)) return p.toString();
            }
            // Common Windows install locations
            Path[] candidates = new Path[] {
                    Path.of("C:/Program Files/ffmpeg/bin/ffmpeg.exe"),
                    Path.of("C:/Program Files (x86)/ffmpeg/bin/ffmpeg.exe"),
                    Path.of("C:/ffmpeg/bin/ffmpeg.exe")
            };
            for (Path c : candidates) {
                try { if (Files.exists(c)) return c.toString(); } catch (Exception ignore) {}
            }
        } catch (Exception ignore) {}
        // Fallback to PATH
        return "ffmpeg";
    }

    @Override
    public String transcribe(Path audioFile) throws Exception {
        Credentials creds = resolveCredentials();

        SpeechSettings.Builder speechSettings = SpeechSettings.newBuilder();
        if (creds != null) {
            speechSettings.setCredentialsProvider(FixedCredentialsProvider.create(creds));
        }
        // Extend polling to handle long audios without cancellation (up to 1 hour)
        RetrySettings lroRetry = RetrySettings.newBuilder()
                .setInitialRetryDelay(Duration.ofSeconds(5))
                .setRetryDelayMultiplier(1.5)
                .setMaxRetryDelay(Duration.ofSeconds(45))
                .setTotalTimeout(Duration.ofHours(1))
                .build();
        speechSettings
                .longRunningRecognizeOperationSettings()
                .setPollingAlgorithm(OperationTimedPollAlgorithm.create(lroRetry));

        // Convert to 16kHz mono LINEAR16 WAV to improve recognition reliability
        Path wav = convertToWav16kMono(audioFile);
        try (SpeechClient speech = SpeechClient.create(speechSettings.build())) {
            long size = Files.size(wav);
            RecognitionConfig config = RecognitionConfig.newBuilder()
                    .setLanguageCode(languageCode)
                    .setEnableAutomaticPunctuation(true)
                    .setEncoding(RecognitionConfig.AudioEncoding.LINEAR16)
                    .setSampleRateHertz(16000)
                    .setAudioChannelCount(1)
                    .build();

            if (size <= SYNC_MAX_BYTES) {
                byte[] content = Files.readAllBytes(wav);
                RecognitionAudio audio = RecognitionAudio.newBuilder()
                        .setContent(ByteString.copyFrom(content))
                        .build();
                RecognizeRequest request = RecognizeRequest.newBuilder()
                        .setConfig(config)
                        .setAudio(audio)
                        .build();
                RecognizeResponse response = speech.recognize(request);
                return joinResults(response.getResultsList());
            }

            if (bucketName == null || bucketName.isBlank()) {
                // Fallback: segment the WAV and run sync recognition per chunk
                log.warn("Large audio without GCS bucket configured; falling back to chunked sync recognition.");
                return transcribeByChunks(speech, config, wav);
            }

            String gcsUri = uploadToGcs(creds, wav);
            try {
                RecognitionAudio audio = RecognitionAudio.newBuilder().setUri(gcsUri).build();
                LongRunningRecognizeRequest lrReq = LongRunningRecognizeRequest.newBuilder()
                        .setConfig(config)
                        .setAudio(audio)
                        .build();
                OperationFuture<LongRunningRecognizeResponse, LongRunningRecognizeMetadata> future = speech.longRunningRecognizeAsync(lrReq);
                // Wait using configured LRO polling (up to 1 hour as configured above)
                LongRunningRecognizeResponse lrResp = future.get();
                return joinResults(lrResp.getResultsList());
            } finally {
                deleteFromGcs(creds, gcsUri);
            }
        } finally {
            try { Files.deleteIfExists(wav); } catch (Exception ignore) {}
        }
    }

    private String transcribeByChunks(SpeechClient speech, RecognitionConfig config, Path wav) throws Exception {
        Path chunkDir = Files.createDirectories(Path.of("build", "asr-tmp", "chunks-" + UUID.randomUUID()));
        Path pattern = chunkDir.resolve("chunk-%03d.wav");
        String ffmpegCmd = resolveFfmpegCmd();
        String[] cmd = new String[]{
                ffmpegCmd,
                "-y",
                "-i", wav.toAbsolutePath().toString(),
                "-f", "segment",
                "-segment_time", "55",
                "-c", "copy",
                pattern.toAbsolutePath().toString()
        };
        Process p;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        } catch (IOException io) {
            throw new IllegalStateException("Failed to start ffmpeg at '" + ffmpegCmd + "'. Ensure ffmpeg is installed or set app.ffmpeg.path / FFMPEG_PATH / APP_FFMPEG_PATH.", io);
        }
        try (BufferedInputStream bis = new BufferedInputStream(p.getInputStream())) {
            bis.readAllBytes();
        }
        int code = p.waitFor();
        if (code != 0) {
            throw new IllegalStateException("ffmpeg failed to segment audio (exit=" + code + "). Command='" + ffmpegCmd + "'. Consider setting app.ffmpeg.path for an absolute path.");
        }
        // Collect chunks in order
        ArrayList<Path> chunks = new ArrayList<>();
        try (Stream<Path> stream = Files.list(chunkDir)) {
            stream.filter(f -> f.getFileName().toString().startsWith("chunk-") && f.toString().endsWith(".wav"))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(chunks::add);
        }
        if (chunks.isEmpty()) {
            throw new IllegalStateException("Audio segmentation produced no chunks.");
        }
        StringBuilder sb = new StringBuilder();
        for (Path c : chunks) {
            byte[] bytes = Files.readAllBytes(c);
            RecognitionAudio audio = RecognitionAudio.newBuilder().setContent(ByteString.copyFrom(bytes)).build();
            RecognizeRequest req = RecognizeRequest.newBuilder().setConfig(config).setAudio(audio).build();
            RecognizeResponse resp = speech.recognize(req);
            String part = joinResults(resp.getResultsList());
            if (!part.isBlank()) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(part);
            }
        }
        // Cleanup
        try (Stream<Path> stream = Files.list(chunkDir)) {
            stream.forEach(f -> { try { Files.deleteIfExists(f); } catch (Exception ignore) {} });
        } catch (Exception ignore) {}
        try { Files.deleteIfExists(chunkDir); } catch (Exception ignore) {}
        return sb.toString().trim();
    }

    private Path convertToWav16kMono(Path input) throws Exception {
        Path tempDir = Files.createDirectories(Path.of("build", "asr-tmp"));
        Path out = tempDir.resolve("gcp-" + java.util.UUID.randomUUID() + ".wav");
        String ffmpegCmd = resolveFfmpegCmd();
        String[] cmd = new String[]{
                ffmpegCmd,
                "-y",
                "-i", input.toAbsolutePath().toString(),
                "-ac", "1",
                "-ar", "16000",
                "-f", "wav",
                "-acodec", "pcm_s16le",
                out.toAbsolutePath().toString()
        };
        Process p;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        } catch (IOException io) {
            throw new IllegalStateException("Failed to start ffmpeg at '" + ffmpegCmd + "'. Ensure ffmpeg is installed or set app.ffmpeg.path / FFMPEG_PATH / APP_FFMPEG_PATH.", io);
        }
        try (BufferedInputStream bis = new BufferedInputStream(p.getInputStream())) {
            bis.readAllBytes();
        }
        int code = p.waitFor();
        if (code != 0) {
            throw new IllegalStateException("ffmpeg failed (" + code + ") converting audio. Command='" + ffmpegCmd + "'. Ensure ffmpeg is installed or set app.ffmpeg.path/FFMPEG_PATH/APP_FFMPEG_PATH.");
        }
        return out;
    }

    private String joinResults(List<SpeechRecognitionResult> results) {
        return results.stream()
                .map(SpeechRecognitionResult::getAlternativesList)
                .filter(list -> !list.isEmpty())
                .map(list -> list.get(0))
                .map(SpeechRecognitionAlternative::getTranscript)
                .collect(Collectors.joining(" "))
                .trim();
    }

    private String uploadToGcs(Credentials creds, Path audioFile) throws Exception {
        StorageOptions.Builder b = StorageOptions.newBuilder();
        if (creds != null) b.setCredentials(creds);
        Storage storage = b.build().getService();
        String objectName = "uploads/" + UUID.randomUUID() + "-" + audioFile.getFileName();
        BlobId blobId = BlobId.of(bucketName, objectName);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType("application/octet-stream").build();
        storage.create(blobInfo, Files.readAllBytes(audioFile));
        return "gs://" + bucketName + "/" + objectName;
    }

    private void deleteFromGcs(Credentials creds, String gcsUri) {
        try {
            if (gcsUri == null || !gcsUri.startsWith("gs://")) return;
            String noScheme = gcsUri.substring(5);
            int slash = noScheme.indexOf('/');
            if (slash < 0) return;
            String bucket = noScheme.substring(0, slash);
            String object = noScheme.substring(slash + 1);
            StorageOptions.Builder b = StorageOptions.newBuilder();
            if (creds != null) b.setCredentials(creds);
            Storage storage = b.build().getService();
            storage.delete(BlobId.of(bucket, object));
        } catch (Exception ignore) {
        }
    }

    private Credentials resolveCredentials() throws Exception {
        // 1) Explicit property path
        Path p = tryResolveCredentialsPath();
        if (p != null && Files.exists(p)) {
            try (FileInputStream fis = new FileInputStream(p.toFile())) {
                log.debug("Loading GCP credentials from: {}", p.toAbsolutePath());
                return ServiceAccountCredentials.fromStream(fis);
            }
        }
        // 2) GOOGLE_APPLICATION_CREDENTIALS env (explicit)
        String gac = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
        if (gac != null && !gac.isBlank()) {
            Path envPath = Path.of(gac);
            if (Files.exists(envPath)) {
                try (FileInputStream fis = new FileInputStream(envPath.toFile())) {
                    log.debug("Loading GCP credentials from GOOGLE_APPLICATION_CREDENTIALS: {}", envPath.toAbsolutePath());
                    return ServiceAccountCredentials.fromStream(fis);
                }
            }
        }
        // 3) Fall back to ADC (may use ADC providers like gcloud login)
        try {
            log.debug("Falling back to Google Application Default Credentials...");
            return GoogleCredentials.getApplicationDefault();
        } catch (Exception e) {
            String wd = System.getProperty("user.dir");
            throw new IllegalStateException(
                    "No GCP credentials found. Set app.gcp.credentials-path or GOOGLE_APPLICATION_CREDENTIALS. " +
                    "Tried: property path, env var, and ADC. Working dir=" + wd,
                    e);
        }
    }

    private Path tryResolveCredentialsPath() {
        try {
            // Prefer configured path
            if (credentialsPath != null && !credentialsPath.isBlank()) {
                return Path.of(credentialsPath);
            }
            // Try .env-provided alt env var if present
            String alt = System.getenv("GCP_CREDENTIALS_PATH");
            if (alt != null && !alt.isBlank()) {
                Path p = Path.of(alt);
                if (Files.exists(p)) return p;
            }
            // Try common local files
            Path wd = Path.of(System.getProperty("user.dir"));
            Path[] candidates = new Path[] {
                    wd.resolve("cred.json"),
                    wd.resolve("Unthinkable").resolve("cred.json"),
                    Path.of("C:/Users/divya/OneDrive/Desktop/summarizeProject/Unthinkable/cred.json")
            };
            for (Path c : candidates) {
                if (Files.exists(c)) return c;
            }
        } catch (Exception ignore) {}
        return null;
    }

    public record LongRunningHandle(String operationName, String gcsUri) {}

    public LongRunningHandle startLongRunning(Path audioFile) throws Exception {
        Credentials creds = resolveCredentials();
        SpeechSettings.Builder speechSettings = SpeechSettings.newBuilder();
        if (creds != null) {
            speechSettings.setCredentialsProvider(FixedCredentialsProvider.create(creds));
        }
        // Convert to 16kHz mono LINEAR16 WAV
        Path wav = convertToWav16kMono(audioFile);
        try (SpeechClient speech = SpeechClient.create(speechSettings.build())) {
            if (bucketName == null || bucketName.isBlank()) {
                throw new IllegalStateException("app.gcp.bucket is required for long-running recognition");
            }
            String gcsUri = uploadToGcs(creds, wav);
            RecognitionConfig config = RecognitionConfig.newBuilder()
                    .setLanguageCode(languageCode)
                    .setEnableAutomaticPunctuation(true)
                    .setEncoding(RecognitionConfig.AudioEncoding.LINEAR16)
                    .setSampleRateHertz(16000)
                    .setAudioChannelCount(1)
                    .build();
            RecognitionAudio audio = RecognitionAudio.newBuilder().setUri(gcsUri).build();
            LongRunningRecognizeRequest lrReq = LongRunningRecognizeRequest.newBuilder()
                    .setConfig(config)
                    .setAudio(audio)
                    .build();
            OperationFuture<LongRunningRecognizeResponse, LongRunningRecognizeMetadata> future = speech.longRunningRecognizeAsync(lrReq);
            String opName = future.getName();
            log.info("Started GCP ASR LRO: {}", opName);
            return new LongRunningHandle(opName, gcsUri);
        } finally {
            try { Files.deleteIfExists(wav); } catch (Exception ignore) {}
        }
    }

    public String pollOperation(String operationName) throws Exception {
        Credentials creds = resolveCredentials();
        SpeechSettings.Builder speechSettings = SpeechSettings.newBuilder();
        if (creds != null) {
            speechSettings.setCredentialsProvider(FixedCredentialsProvider.create(creds));
        }
        try (SpeechClient speech = SpeechClient.create(speechSettings.build())) {
            OperationsClient ops = speech.getOperationsClient();
            Operation op = ops.getOperation(operationName);
            if (!op.getDone()) {
                return null; // still running
            }
            if (op.hasError()) {
                throw new IllegalStateException("Transcription failed: code=" + op.getError().getCode() + ", msg=" + op.getError().getMessage());
            }
            LongRunningRecognizeResponse response = op.getResponse().unpack(LongRunningRecognizeResponse.class);
            return joinResults(response.getResultsList());
        }
    }

    public void deleteGcsObject(String gcsUri) {
        try {
            Credentials creds = resolveCredentials();
            deleteFromGcs(creds, gcsUri);
        } catch (Exception e) {
            log.warn("Failed to delete GCS object {}: {}", gcsUri, e.toString());
        }
    }
}
