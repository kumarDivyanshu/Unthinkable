package com.Unthinkable.Summarizer.service;

import com.Unthinkable.Summarizer.model.Meeting;
import com.Unthinkable.Summarizer.model.Summary;
import com.Unthinkable.Summarizer.model.Transcript;
import com.Unthinkable.Summarizer.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.io.ByteArrayOutputStream;

@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender mailSender;
    private final boolean enabled;
    private final String from;
    private final boolean attachPdf;

    public MailService(JavaMailSender mailSender,
                       @Value("${app.mail.enabled:false}") boolean enabled,
                       @Value("${app.mail.from:noreply@example.com}") String from,
                       @Value("${app.mail.attach-pdf:false}") boolean attachPdf) {
        this.mailSender = mailSender;
        this.enabled = enabled;
        this.from = from;
        this.attachPdf = attachPdf;
    }

    public void sendMeetingSummary(User user, Meeting meeting, Transcript transcript, Summary summary) {
        if (!enabled) {
            return;
        }
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            log.warn("Mail: user email missing; skipping email for meeting {}", meeting != null ? meeting.getMeetingId() : "?");
            return;
        }
        // avoid sending to placeholder guest email
        if ("guest@local".equalsIgnoreCase(user.getEmail())) {
            log.info("Mail: guest user; skipping email for meeting {}", meeting != null ? meeting.getMeetingId() : "?");
            return;
        }
        try {
            String subject = "Your meeting '" + (meeting != null ? meeting.getTitle() : "Meeting") + "' summary";
            String html = buildHtml(user, meeting, summary, transcript);

            MimeMessage mime = mailSender.createMimeMessage();
            boolean multipart = attachPdf; // only need multipart if attaching pdf
            MimeMessageHelper helper = new MimeMessageHelper(mime, multipart, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(user.getEmail());
            helper.setSubject(subject);
            helper.setText(html, true); // HTML

            if (attachPdf) {
                byte[] pdf = renderPdf(html);
                if (pdf != null && pdf.length > 0) {
                    helper.addAttachment("meeting-" + (meeting != null ? meeting.getMeetingId() : "summary") + ".pdf",
                            new ByteArrayResource(pdf));
                }
            }

            mailSender.send(mime);
            log.info("Mail: sent HTML summary{} to {} for meeting {}",
                    attachPdf ? "+PDF" : "",
                    user.getEmail(),
                    meeting != null ? meeting.getMeetingId() : "?");
        } catch (Exception e) {
            log.error("Mail: failed to send summary email: {}", e.toString(), e);
        }
    }

    private String buildHtml(User user, Meeting meeting, Summary summary, Transcript transcript) {
        String title = meeting != null && meeting.getTitle() != null && !meeting.getTitle().isBlank() ? meeting.getTitle() : "Meeting";
        String userName = user != null && user.getFullName() != null ? user.getFullName() : "there";
        String sum = summary != null && summary.getSummaryText() != null ? escape(summary.getSummaryText()) : "No summary available.";
        String decisions = summary != null && summary.getKeyDecisions() != null ? escape(summary.getKeyDecisions()) : "-";
        String transcriptText = transcript != null && transcript.getTranscriptText() != null ? escape(transcript.getTranscriptText()) : "";
        boolean hasTranscript = !transcriptText.isBlank();

        return "" +
                "<!doctype html>" +
                "<html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'>" +
                "<style>" +
                "body{margin:0;padding:24px;background:#f4f6fb;color:#1f2937;font-family:Segoe UI,Roboto,Arial,Helvetica,sans-serif}" +
                ".wrap{max-width:760px;margin:0 auto;background:#ffffff;border:1px solid #e6e8ef;border-radius:14px;overflow:hidden;box-shadow:0 8px 28px rgba(16,24,40,.08)}" +
                ".header{background:linear-gradient(135deg,#4f46e5,#06b6d4);padding:22px 26px;color:#fff;position:relative}" +
                ".brand{display:flex;align-items:center;gap:14px}" +
                ".logo{display:inline-flex;align-items:center;justify-content:center;width:36px;height:36px;border-radius:10px;background:rgba(255,255,255,.18);backdrop-filter:saturate(180%) blur(8px);font-size:18px}" +
                ".brand-title{font-weight:700;font-size:18px;letter-spacing:.2px}" +
                ".brand-sub{opacity:.95;font-size:13px;margin-top:2px}" +
                ".meta{color:#6b7280;font-size:12.5px;margin:10px 0 0}" +
                ".body{padding:22px 26px}" +
                ".hello{margin:0 0 14px;color:#374151;font-size:14px}" +
                ".grid{display:grid;grid-template-columns:1fr;gap:14px}" +
                "@media (min-width:700px){.grid{grid-template-columns:1fr 1fr}}" +
                ".section{background:#ffffff;border:1px solid #eceef5;border-radius:12px;padding:14px 14px 12px 14px;box-shadow:0 2px 10px rgba(17,24,39,.04);position:relative}" +
                ".section:before{content:'';position:absolute;left:0;top:0;bottom:0;width:4px;border-radius:12px 0 0 12px;background:#c7d2fe}" +
                ".section.purple:before{background:#a78bfa}.section.teal:before{background:#5eead4}.section.slate:before{background:#93c5fd}" +
                ".sec-head{display:flex;align-items:center;gap:10px;margin:2px 0 10px}" +
                ".badge{width:26px;height:26px;border-radius:8px;display:inline-flex;align-items:center;justify-content:center;font-size:14px;font-weight:700;color:#111827}" +
                ".purple .badge{background:#ede9fe}.teal .badge{background:#d1fae5}.slate .badge{background:#dbeafe}" +
                ".sec-title{font-size:15px;font-weight:700;color:#111827}" +
                "pre{white-space:pre-wrap;background:#f9fafb;border:1px dashed #e5e7eb;border-radius:8px;padding:12px;line-height:1.5;color:#111827;font-size:13.5px}" +
                ".footer{padding:14px 26px 22px;color:#6b7280;font-size:12.5px;border-top:1px solid #eef0f6;background:#fafbfe}" +
                "</style></head><body>" +
                "<div class='wrap'>" +
                "  <div class='header'>" +
                "    <div class='brand'>" +
                "      <div class='logo'>✦</div>" +
                "      <div>" +
                "        <div class='brand-title'>Meeting Summary</div>" +
                "        <div class='brand-sub'>" + escape(title) + "</div>" +
                "      </div>" +
                "    </div>" +
                "    <div class='meta'>Meeting ID: " + (meeting != null ? meeting.getMeetingId() : "-") + "</div>" +
                "  </div>" +
                "  <div class='body'>" +
                "    <p class='hello'>Hi " + escape(userName) + ", here's your processed meeting:</p>" +
                "    <div class='grid'>" +
                "      <div class='section purple'>" +
                "        <div class='sec-head'><div class='badge'>📝</div><div class='sec-title'>Summary</div></div>" +
                "        <pre>" + sum + "</pre>" +
                "      </div>" +
                "      <div class='section teal'>" +
                "        <div class='sec-head'><div class='badge'>✅</div><div class='sec-title'>Key Decisions</div></div>" +
                "        <pre>" + decisions + "</pre>" +
                "      </div>" +
                (hasTranscript ?
                "      <div class='section slate' style='grid-column:1 / -1'>" +
                "        <div class='sec-head'><div class='badge'>🗒️</div><div class='sec-title'>Transcript (truncated)</div></div>" +
                "        <pre>" + truncate(transcriptText, 8000) + "</pre>" +
                "      </div>" : "") +
                "    </div>" +
                "  </div>" +
                "  <div class='footer'>This is an automated email. Please do not reply.</div>" +
                "</div>" +
                "</body></html>";
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private String escape(String s) {
        if (s == null) return "";
        return s
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private byte[] renderPdf(String html) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("Mail: failed to render PDF: {}", e.toString());
            return null;
        }
    }
}
