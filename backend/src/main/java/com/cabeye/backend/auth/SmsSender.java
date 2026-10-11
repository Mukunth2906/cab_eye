package com.cabeye.backend.auth;

import com.cabeye.backend.account.Phone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Sends the sign-in code as a real text message.
 *
 * <h2>Providers</h2>
 * Chosen by {@code cabeye.sms.provider}: {@code twilio}, {@code fast2sms}, {@code none}, or
 * {@code auto} (the default — Twilio if its keys are set, else Fast2SMS if its key is set, else
 * none). With no provider the code is not texted; {@link OtpService} then falls back to its
 * mock delivery, and the app shows the code on screen as an incoming message.
 *
 * <ul>
 *   <li><b>Twilio</b> — {@code cabeye.sms.twilio.account-sid}, {@code .auth-token} and
 *       {@code .from} (a Twilio number such as +1415…, or a Messaging Service SID MG…).
 *       A trial account only texts numbers verified in the Twilio console.</li>
 *   <li><b>Fast2SMS</b> (India) — {@code cabeye.sms.fast2sms.api-key}. Uses the Quick SMS
 *       route, which needs no DLT template.</li>
 * </ul>
 *
 * The message deliberately carries the code as a plain six-digit number: Android's SMS User
 * Consent API, which the app uses to read the code without the rider touching anything, only
 * offers messages that contain one.
 *
 * <p>Keys are never logged; numbers are logged masked.
 */
@Service
public class SmsSender {

    private static final Logger log = LoggerFactory.getLogger(SmsSender.class);

    private final String provider;
    private final String twilioSid;
    private final String twilioToken;
    private final String twilioFrom;
    private final String twilioBase;
    private final String fast2smsKey;
    private final String fast2smsUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public SmsSender(@Value("${cabeye.sms.provider:auto}") String provider,
                     @Value("${cabeye.sms.twilio.account-sid:}") String twilioSid,
                     @Value("${cabeye.sms.twilio.auth-token:}") String twilioToken,
                     @Value("${cabeye.sms.twilio.from:}") String twilioFrom,
                     @Value("${cabeye.sms.twilio.api-base:https://api.twilio.com}") String twilioBase,
                     @Value("${cabeye.sms.fast2sms.api-key:}") String fast2smsKey,
                     @Value("${cabeye.sms.fast2sms.url:https://www.fast2sms.com/dev/bulkV2}") String fast2smsUrl) {
        this.twilioSid = twilioSid.trim();
        this.twilioToken = twilioToken.trim();
        this.twilioFrom = twilioFrom.trim();
        this.twilioBase = twilioBase.trim();
        this.fast2smsKey = fast2smsKey.trim();
        this.fast2smsUrl = fast2smsUrl.trim();
        this.provider = resolve(provider.trim().toLowerCase());
        log.info("SMS provider={}", this.provider);
    }

    private String resolve(String wanted) {
        boolean twilio = !twilioSid.isEmpty() && !twilioToken.isEmpty() && !twilioFrom.isEmpty();
        boolean fast2sms = !fast2smsKey.isEmpty();
        return switch (wanted) {
            case "twilio" -> twilio ? "twilio" : "none";
            case "fast2sms" -> fast2sms ? "fast2sms" : "none";
            case "none", "mock", "off" -> "none";
            default -> twilio ? "twilio" : fast2sms ? "fast2sms" : "none";
        };
    }

    /** Which provider is in use: "twilio", "fast2sms" or "none". */
    public String provider() {
        return provider;
    }

    public boolean configured() {
        return !"none".equals(provider);
    }

    /** The text of the message, shared with the app's on-screen preview of a mock delivery. */
    public static String messageFor(String code) {
        return "Your Cab Eye code is " + code + ". It expires in 5 minutes. Do not share it with anyone.";
    }

    /**
     * Texts {@code code} to the ten-digit Indian mobile {@code phone}.
     *
     * @return null when the provider accepted the message, otherwise a short reason for the log
     */
    public String send(String phone, String code) {
        try {
            return switch (provider) {
                case "twilio" -> sendTwilio(phone, code);
                case "fast2sms" -> sendFast2sms(phone, code);
                default -> "no SMS provider configured";
            };
        } catch (Exception e) {
            log.warn("SMS send failed provider={} phone={}: {}", provider, Phone.masked(phone), e.toString());
            return e.getClass().getSimpleName();
        }
    }

    private String sendTwilio(String phone, String code) throws Exception {
        String form = "To=" + enc("+91" + phone)
            + (twilioFrom.startsWith("MG") ? "&MessagingServiceSid=" : "&From=") + enc(twilioFrom)
            + "&Body=" + enc(messageFor(code));
        String auth = Base64.getEncoder().encodeToString((twilioSid + ":" + twilioToken).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(twilioBase + "/2010-04-01/Accounts/" + twilioSid + "/Messages.json"))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Basic " + auth)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 == 2) {
            log.info("SMS sent provider=twilio phone={}", Phone.masked(phone));
            return null;
        }
        log.warn("SMS refused provider=twilio phone={} status={} body={}", Phone.masked(phone),
            response.statusCode(), abbreviate(response.body()));
        return "twilio HTTP " + response.statusCode();
    }

    private String sendFast2sms(String phone, String code) throws Exception {
        String json = "{\"route\":\"q\",\"message\":\"" + messageFor(code).replace("\"", "'")
            + "\",\"flash\":\"0\",\"numbers\":\"" + phone + "\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(fast2smsUrl))
            .timeout(Duration.ofSeconds(10))
            .header("authorization", fast2smsKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        String body = response.body() == null ? "" : response.body();
        if (response.statusCode() / 100 == 2 && body.replace(" ", "").contains("\"return\":true")) {
            log.info("SMS sent provider=fast2sms phone={}", Phone.masked(phone));
            return null;
        }
        log.warn("SMS refused provider=fast2sms phone={} status={} body={}", Phone.masked(phone),
            response.statusCode(), abbreviate(body));
        return "fast2sms HTTP " + response.statusCode();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
