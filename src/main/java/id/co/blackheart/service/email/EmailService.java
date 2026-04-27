package id.co.blackheart.service.email;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

// Transactional email wrapper. Throws on send failure so callers can fall
// back to logging the URL for manual delivery.
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${app.mail.from}")
    private String fromAddress;

    @Value("${app.mail.from-name:Meridian Edge}")
    private String fromName;

    // Lets dev / CI skip SMTP entirely — caller's WARN-log fallback still
    // surfaces the URL for ops retrieval.
    @Value("${app.mail.enabled:true}")
    private boolean enabled;

    public void sendPasswordReset(String toEmail, String resetUrl, long ttlMinutes) {
        ActionEmail content = new ActionEmail(
                "Reset your Meridian Edge password",
                "Use this link within " + ttlMinutes + " minutes to set a new password.",
                "ACCOUNT SECURITY",
                "Reset your password",
                """
                Someone — hopefully you — asked to reset the password for your Meridian Edge account. \
                Use the button below to set a new one. The link is single-use and expires in %d minutes.\
                """.formatted(ttlMinutes),
                "Set new password",
                resetUrl,
                ttlMinutes + " minutes",
                """
                Didn't request this? You can safely ignore this email — your password won't change. \
                If you suspect someone is trying to access your account, contact support immediately.\
                """
        );
        send(toEmail, content);
    }

    public void sendEmailVerification(String toEmail, String verifyUrl, long ttlHours) {
        ActionEmail content = new ActionEmail(
                "Confirm your email for Meridian Edge",
                "One step left to activate your account.",
                "WELCOME",
                "Confirm your email",
                """
                Welcome to Meridian Edge — algorithmic trading without the spreadsheet. \
                Confirm this email so we can reach you about security alerts and account recovery. \
                The link expires in %d hours.\
                """.formatted(ttlHours),
                "Confirm email",
                verifyUrl,
                ttlHours + " hours",
                """
                Didn't sign up? You can safely ignore this email — no account will be activated \
                without your confirmation.\
                """
        );
        send(toEmail, content);
    }

    private void send(String to, ActionEmail content) {
        if (!enabled) {
            log.info("Email send skipped (app.mail.enabled=false) | subject='{}' to={}", content.subject(), to);
            throw new EmailSendException("Email is disabled by config", null);
        }
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(new InternetAddress(fromAddress, fromName, StandardCharsets.UTF_8.name()));
            helper.setTo(to);
            helper.setSubject(content.subject());
            // Two-arg setText: text fallback + HTML body (multipart/alternative).
            helper.setText(renderText(content), renderHtml(content));
            mailSender.send(message);
            log.info("Email sent | subject='{}' to={}", content.subject(), to);
        } catch (MessagingException | UnsupportedEncodingException | RuntimeException e) {
            log.error("Email send failed | subject='{}' to={}", content.subject(), to, e);
            throw new EmailSendException("Failed to send email", e);
        }
    }

    /**
     * Inputs to the shared transactional-email template. Every Meridian
     * Edge action email is the same shape — eyebrow, title, body, CTA,
     * expiry pill, security note — so the layout is rendered once.
     */
    private record ActionEmail(
            String subject,
            String preheader,
            String eyebrow,
            String title,
            String body,
            String buttonLabel,
            String url,
            String expiresIn,
            String securityNote
    ) {}

    // ─── Plain-text renderer ────────────────────────────────────────────────────

    private static String renderText(ActionEmail c) {
        return """
                MERIDIAN EDGE — %s

                %s

                %s

                %s:
                %s

                Expires in %s.

                %s

                —
                Meridian Edge · Algorithmic trading desk
                """.formatted(
                        c.eyebrow(),
                        c.title(),
                        c.body(),
                        c.buttonLabel(),
                        c.url(),
                        c.expiresIn(),
                        c.securityNote()
                );
    }

    // ─── HTML renderer ─────────────────────────────────────────────────────────
    //
    // Email client compatibility constraints drive the template:
    //   - table-based layout (Outlook ignores most modern CSS)
    //   - inline styles only (Gmail strips <style> blocks under some clients)
    //   - max-width:600px outer table for desktop + mobile resilience
    //   - preheader span hidden via a dozen tricks because every client picks
    //     a different one to honor
    //   - no external assets (some clients block images by default)

    private static String renderHtml(ActionEmail c) {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <meta name="x-apple-disable-message-reformatting">
                  <title>%s</title>
                </head>
                <body style="margin:0;padding:0;background-color:#F5F7FA;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
                  <div style="display:none;visibility:hidden;opacity:0;color:transparent;height:0;width:0;max-height:0;max-width:0;overflow:hidden;mso-hide:all;">%s</div>
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="background-color:#F5F7FA;">
                    <tr>
                      <td align="center" style="padding:32px 16px;">
                        <table role="presentation" width="600" cellpadding="0" cellspacing="0" border="0" style="max-width:600px;background-color:#FFFFFF;border-radius:12px;overflow:hidden;">
                          <tr>
                            <td style="padding:20px 32px;background-color:#0A0B0D;">
                              <table role="presentation" cellpadding="0" cellspacing="0" border="0">
                                <tr>
                                  <td style="vertical-align:middle;">
                                    <span style="display:inline-block;width:30px;height:30px;background-color:#00C896;color:#0A0B0D;border-radius:8px;text-align:center;line-height:30px;font-family:Georgia,serif;font-size:17px;font-weight:bold;">M</span>
                                  </td>
                                  <td style="vertical-align:middle;padding-left:10px;color:#FFFFFF;font-size:14px;font-weight:600;letter-spacing:0.02em;">
                                    Meridian Edge
                                  </td>
                                </tr>
                              </table>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:36px 32px 8px;">
                              <p style="margin:0 0 8px;font-size:11px;letter-spacing:0.18em;text-transform:uppercase;color:#8892A4;font-weight:600;">%s</p>
                              <h1 style="margin:0;font-size:24px;line-height:1.3;color:#0A0B0D;font-weight:600;">%s</h1>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:16px 32px 8px;color:#3D4455;font-size:15px;line-height:1.6;">
                              %s
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:16px 32px 8px;">
                              <table role="presentation" cellpadding="0" cellspacing="0" border="0">
                                <tr>
                                  <td align="center" style="background-color:#00C896;border-radius:8px;">
                                    <a href="%s" style="display:inline-block;padding:14px 28px;color:#0A0B0D;font-size:15px;font-weight:600;text-decoration:none;letter-spacing:0.01em;">%s</a>
                                  </td>
                                </tr>
                              </table>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:8px 32px 16px;color:#8892A4;font-size:13px;">
                              <strong style="color:#3D4455;">Expires in %s.</strong> Request a fresh link if it lapses.
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:0 32px 16px;">
                              <div style="border-top:1px solid #E5E8EE;padding-top:16px;">
                                <p style="margin:0 0 6px;color:#8892A4;font-size:12px;">Or paste this URL into your browser:</p>
                                <p style="margin:0;font-family:'SFMono-Regular',Consolas,Menlo,monospace;font-size:11px;color:#4A5160;word-break:break-all;">%s</p>
                              </div>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:0 32px 32px;">
                              <div style="background-color:#F5F7FA;border-left:3px solid #4E9EFF;padding:12px 16px;border-radius:4px;">
                                <p style="margin:0;color:#3D4455;font-size:13px;line-height:1.5;">%s</p>
                              </div>
                            </td>
                          </tr>
                        </table>
                        <table role="presentation" width="600" cellpadding="0" cellspacing="0" border="0" style="max-width:600px;">
                          <tr>
                            <td style="padding:20px 32px;text-align:center;color:#8892A4;font-size:12px;line-height:1.5;">
                              Meridian Edge &middot; Algorithmic trading desk<br>
                              You received this because of activity on your account.
                            </td>
                          </tr>
                        </table>
                      </td>
                    </tr>
                  </table>
                </body>
                </html>
                """.formatted(
                        escapeHtml(c.subject()),
                        escapeHtml(c.preheader()),
                        escapeHtml(c.eyebrow()),
                        escapeHtml(c.title()),
                        escapeHtml(c.body()),
                        escapeAttr(c.url()),
                        escapeHtml(c.buttonLabel()),
                        escapeHtml(c.expiresIn()),
                        escapeHtml(c.url()),
                        escapeHtml(c.securityNote())
                );
    }

    /**
     * Minimal HTML-text escaping. Inputs to the template are operator-controlled
     * literals today, but escaping defends against future callers passing
     * user-derived strings (e.g. account labels) into the template.
     */
    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /**
     * Attribute-context escaping. Same set as text plus single-quote, since
     * we sometimes drop URLs into href="…" attributes.
     */
    private static String escapeAttr(String s) {
        return escapeHtml(s).replace("'", "&#39;");
    }

    public static class EmailSendException extends RuntimeException {
        public EmailSendException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
