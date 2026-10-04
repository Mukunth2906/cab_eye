package com.cabeye.backend.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tells the admin, off the website, that something needs them now.
 *
 * <h2>Telegram (instant, on the admin's phone)</h2>
 * The bot is created in Telegram with @BotFather; its token goes in
 * {@code cabeye.telegram.bot-token}. The admin's chat is then linked from the admin website:
 * it shows a one-time link, the admin opens it in Telegram and presses Start, and the website
 * picks up that chat. Only someone who can see the admin website can link a chat, so a
 * stranger who finds the bot cannot subscribe to alerts.
 *
 * <h2>Email (record and backup)</h2>
 * Gmail with an app password, via Spring Mail ({@code spring.mail.*}). Sent to
 * {@code cabeye.admin.email}.
 *
 * <p>Everything runs on one background thread: a rider's feedback or SOS request never waits
 * for Telegram or Gmail, and a failure is recorded on the case rather than thrown.
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final long LINK_TTL_MS = 10L * 60 * 1000;

    private final String botToken;
    private final String apiBase;
    private final String adminEmail;
    private final String mailFrom;
    private final String adminUrl;
    private final ObjectProvider<JavaMailSender> mail;
    private final AdminSettings settings;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cabeye-alerts");
        t.setDaemon(true);
        return t;
    });
    private final SecureRandom random = new SecureRandom();

    private volatile String linkCode;
    private volatile long linkCodeExpires;

    public AlertService(@Value("${cabeye.telegram.bot-token:}") String botToken,
                        @Value("${cabeye.telegram.api-base:https://api.telegram.org}") String apiBase,
                        @Value("${cabeye.admin.email:}") String adminEmail,
                        @Value("${spring.mail.username:}") String mailFrom,
                        @Value("${cabeye.admin.url:http://localhost:8080/admin/}") String adminUrl,
                        ObjectProvider<JavaMailSender> mail,
                        AdminSettings settings,
                        ObjectMapper json) {
        this.botToken = botToken == null ? "" : botToken.trim();
        this.apiBase = apiBase.replaceAll("/+$", "");
        this.adminEmail = adminEmail == null ? "" : adminEmail.trim();
        this.mailFrom = mailFrom == null ? "" : mailFrom.trim();
        this.adminUrl = adminUrl;
        this.mail = mail;
        this.settings = settings;
        this.json = json;
        log.info("ALERTS telegram={} email={}", telegramState(), emailConfigured() ? "ready" : "not set up");
    }

    // -----------------------------------------------------------------------------------
    //  State
    // -----------------------------------------------------------------------------------

    public boolean telegramConfigured() {
        return !botToken.isEmpty();
    }

    public boolean telegramLinked() {
        return telegramConfigured() && settings.get(AdminSettings.TELEGRAM_CHAT).isPresent();
    }

    public boolean emailConfigured() {
        return !adminEmail.isEmpty() && !mailFrom.isEmpty() && mail.getIfAvailable() != null;
    }

    String telegramState() {
        if (!telegramConfigured()) return "no bot token";
        return telegramLinked() ? "linked" : "bot set, chat not linked";
    }

    public Map<String, Object> state() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("telegramBotSet", telegramConfigured());
        s.put("telegramLinked", telegramLinked());
        s.put("telegramChat", settings.get(AdminSettings.TELEGRAM_NAME).orElse(null));
        s.put("emailReady", emailConfigured());
        s.put("emailTo", adminEmail.isEmpty() ? null : adminEmail);
        return s;
    }

    // -----------------------------------------------------------------------------------
    //  Alerts
    // -----------------------------------------------------------------------------------

    /** Sends the case to Telegram and email in the background; completes with what happened. */
    public CompletableFuture<String> alert(AdminCase c) {
        String subject = subjectFor(c);
        String body = bodyFor(c);
        return CompletableFuture.supplyAsync(() -> sendBoth(subject, body), worker);
    }

    /** "Send a test alert" on the website. Waits (briefly) so the admin sees the result. */
    public String test() {
        String body = "This is a test alert from Cab Eye. If you can read this, SOS and urgent feedback will reach you here.\n"
                + adminUrl;
        try {
            return CompletableFuture.supplyAsync(() -> sendBoth("Cab Eye test alert", body), worker)
                    .get(25, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return "Timed out: " + e.getClass().getSimpleName();
        }
    }

    /** Email only (daily summary). Background. */
    public void email(String subject, String body) {
        worker.submit(() -> {
            String r = sendEmail(subject, body);
            log.info("ALERT_EMAIL \"{}\" -> {}", subject, r);
        });
    }

    private String sendBoth(String subject, String body) {
        List<String> parts = new ArrayList<>();
        parts.add("Telegram " + sendTelegram(subject + "\n" + body));
        parts.add("email " + sendEmail(subject, body));
        String result = String.join(" · ", parts);
        log.info("ALERT \"{}\" -> {}", subject, result);
        return result;
    }

    String subjectFor(AdminCase c) {
        String what = c.kind == AdminCase.Kind.SOS ? "SOS"
                : (c.kind == AdminCase.Kind.DRIVER_REPORT ? "Driver's report on rider" : "Feedback")
                + (c.category == null ? "" : " (" + c.category + ")");
        return (c.urgent ? "URGENT · " : "") + what + " · " + (c.rideId == null ? "no ride" : c.rideId);
    }

    String bodyFor(AdminCase c) {
        StringBuilder b = new StringBuilder();
        if (c.rating != null) b.append("Rating: ").append(c.rating).append("/5\n");
        if (c.text != null) {
            b.append(c.kind == AdminCase.Kind.DRIVER_REPORT ? "Driver said: \"" : "Rider said: \"")
                    .append(c.text).append("\"\n");
        }
        if (c.fareRupees != null && c.fareRupees > 0) {
            b.append("Trip: ");
            if (c.distanceMeters != null && c.distanceMeters > 0) {
                b.append(String.format(java.util.Locale.ROOT, "%.1f km", c.distanceMeters / 1000.0));
                if ("ESTIMATE".equals(c.distanceSource)) b.append(" (estimate)");
                b.append(" · ");
            }
            b.append("Rs ").append(c.fareRupees);
            if (c.durationMinutes != null && c.durationMinutes > 0) b.append(" · ").append(c.durationMinutes).append(" min");
            b.append('\n');
        }
        if (c.latitude != null && c.longitude != null) {
            b.append("Location: https://maps.google.com/?q=").append(c.latitude).append(',').append(c.longitude).append('\n');
        }
        b.append("Rider: ").append(orDash(c.riderName)).append(", ").append(orDash(c.riderPhone)).append('\n');
        if (c.emergencyContactPhone != null) {
            b.append("Emergency contact: ").append(orDash(c.emergencyContactName)).append(", ")
                    .append(c.emergencyContactPhone).append('\n');
        }
        if (c.driverId != null) {
            b.append("Driver: ").append(orDash(c.driverName)).append(", ").append(orDash(c.driverPhone))
                    .append(" · ").append(orDash(c.vehiclePlate))
                    .append(c.vehicle == null ? "" : " (" + c.vehicle + ")").append('\n');
        }
        b.append("Open: ").append(adminUrl).append("#case=").append(c.id);
        return b.toString();
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    // -----------------------------------------------------------------------------------
    //  Telegram
    // -----------------------------------------------------------------------------------

    String sendTelegram(String text) {
        if (!telegramConfigured()) return "not set up";
        String chat = settings.get(AdminSettings.TELEGRAM_CHAT).orElse(null);
        if (chat == null) return "not linked";
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("chat_id", chat);
            body.put("text", text.length() > 4000 ? text.substring(0, 4000) : text);
            body.put("disable_web_page_preview", true);
            JsonNode r = call("sendMessage", body);
            return r.path("ok").asBoolean() ? "sent" : "refused (" + r.path("description").asText("?") + ")";
        } catch (Exception e) {
            return "failed (" + e.getClass().getSimpleName() + ")";
        }
    }

    /** Step 1 of linking: a one-time Start link for the admin to open in Telegram. */
    public Map<String, Object> startLink() throws Exception {
        if (!telegramConfigured()) throw new IllegalStateException("Add cabeye.telegram.bot-token first.");
        JsonNode me = call("getMe", null);
        if (!me.path("ok").asBoolean()) {
            throw new IllegalStateException("Telegram refused the bot token: " + me.path("description").asText("?"));
        }
        String username = me.path("result").path("username").asText();
        StringBuilder code = new StringBuilder("link");
        for (int i = 0; i < 10; i++) code.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
        linkCode = code.toString();
        linkCodeExpires = System.currentTimeMillis() + LINK_TTL_MS;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bot", "@" + username);
        out.put("link", "https://t.me/" + username + "?start=" + linkCode);
        out.put("expiresInMinutes", LINK_TTL_MS / 60_000);
        return out;
    }

    /** Step 2: looks for the Start message from step 1 and links that chat. */
    public boolean checkLink() throws Exception {
        String code = linkCode;
        if (code == null || System.currentTimeMillis() > linkCodeExpires) {
            throw new IllegalStateException("The link has expired. Press \"Link Telegram\" again.");
        }
        JsonNode updates = call("getUpdates?timeout=0", null);
        long lastUpdate = -1;
        String chatId = null;
        String chatName = null;
        for (JsonNode u : updates.path("result")) {
            lastUpdate = Math.max(lastUpdate, u.path("update_id").asLong());
            JsonNode msg = u.path("message");
            String text = msg.path("text").asText("");
            JsonNode chat = msg.path("chat");
            if ("private".equals(chat.path("type").asText()) && text.trim().equals("/start " + code)) {
                chatId = chat.path("id").asText();
                chatName = (chat.path("first_name").asText("") + " " + chat.path("last_name").asText("")).trim();
                if (!chat.path("username").asText("").isEmpty()) chatName += " (@" + chat.path("username").asText() + ")";
            }
        }
        // Mark everything read so the next check starts clean.
        if (lastUpdate >= 0) call("getUpdates?timeout=0&offset=" + (lastUpdate + 1), null);
        if (chatId == null) return false;

        settings.put(AdminSettings.TELEGRAM_CHAT, chatId);
        settings.put(AdminSettings.TELEGRAM_NAME, chatName.isBlank() ? "your Telegram" : chatName);
        linkCode = null;
        log.info("ALERTS telegram linked to {}", chatName);
        sendTelegram("Cab Eye: this chat will now get SOS and urgent feedback alerts.");
        return true;
    }

    public void unlink() {
        settings.remove(AdminSettings.TELEGRAM_CHAT);
        settings.remove(AdminSettings.TELEGRAM_NAME);
        log.info("ALERTS telegram unlinked");
    }

    private JsonNode call(String method, Map<String, Object> body) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(apiBase + "/bot" + botToken + "/" + method))
                .timeout(Duration.ofSeconds(15));
        if (body == null) {
            req.GET();
        } else {
            req.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8));
        }
        HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return json.readTree(res.body());
    }

    // -----------------------------------------------------------------------------------
    //  Email
    // -----------------------------------------------------------------------------------

    String sendEmail(String subject, String body) {
        JavaMailSender sender = mail.getIfAvailable();
        if (sender == null || adminEmail.isEmpty() || mailFrom.isEmpty()) return "not set up";
        try {
            SimpleMailMessage m = new SimpleMailMessage();
            m.setFrom(mailFrom);
            m.setTo(adminEmail);
            m.setSubject("[Cab Eye] " + subject);
            m.setText(body);
            sender.send(m);
            return "sent";
        } catch (Exception e) {
            log.warn("ALERT_EMAIL failed: {}", e.toString());
            return "failed (" + e.getClass().getSimpleName() + ")";
        }
    }

}
