package com.Unthinkable.Summarizer.service.asr;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.longrunning.OperationFuture;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class GcpAsrService implements AsrService {

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

    @Override
    public String transcribe(Path audioFile) throws Exception {
        Credentials creds;
        if (credentialsPath != null && !credentialsPath.isBlank()) {
            try (FileInputStream fis = new FileInputStream(credentialsPath)) {
                creds = ServiceAccountCredentials.fromStream(fis);
            }
        } else {
            creds = GoogleCredentials.getApplicationDefault();
        }

        SpeechSettings.Builder speechSettings = SpeechSettings.newBuilder();
        if (creds != null) {
            speechSettings.setCredentialsProvider(FixedCredentialsProvider.create(creds));
        }

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
                throw new IllegalStateException("Audio exceeds sync limit. Configure app.gcp.bucket to enable long-running recognition via GCS (gs://). ");
            }

            String gcsUri = uploadToGcs(creds, wav);
            try {
                RecognitionAudio audio = RecognitionAudio.newBuilder().setUri(gcsUri).build();
                LongRunningRecognizeRequest lrReq = LongRunningRecognizeRequest.newBuilder()
                        .setConfig(config)
                        .setAudio(audio)
                        .build();
                OperationFuture<LongRunningRecognizeResponse, LongRunningRecognizeMetadata> future = speech.longRunningRecognizeAsync(lrReq);
                // Wait up to 15 minutes
                LongRunningRecognizeResponse lrResp = future.get(15, TimeUnit.MINUTES);
                return joinResults(lrResp.getResultsList());
            } finally {
                deleteFromGcs(creds, gcsUri);
            }
        } finally {
            try { Files.deleteIfExists(wav); } catch (Exception ignore) {}
        }
    }

    private Path convertToWav16kMono(Path input) throws Exception {
        Path tempDir = Files.createDirectories(Path.of("build", "asr-tmp"));
        Path out = tempDir.resolve("gcp-" + java.util.UUID.randomUUID() + ".wav");
        String[] cmd = new String[]{
                ffmpegPath,
                "-y",
                "-i", input.toAbsolutePath().toString(),
                "-ac", "1",
                "-ar", "16000",
                "-f", "wav",
                "-acodec", "pcm_s16le",
                out.toAbsolutePath().toString()
        };
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        try (BufferedInputStream bis = new BufferedInputStream(p.getInputStream())) {
            bis.readAllBytes();
        }
        int code = p.waitFor();
        if (code != 0) {
            throw new IllegalStateException("ffmpeg failed (" + code + ") converting audio. Ensure ffmpeg is installed or set app.ffmpeg.path/FFMPEG_PATH.");
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
}
