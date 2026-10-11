package com.cabeye.backend;

import com.cabeye.backend.account.Role;
import com.cabeye.backend.auth.OtpService;
import com.cabeye.backend.auth.SmsSender;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real SMS delivery of sign-in codes, against a stand-in Twilio running on this machine.
 */
class SmsDeliveryTest {

    private HttpServer server;
    private final List<String> received = new ArrayList<>();
    private volatile int status = 201;

    @BeforeEach
    void startFakeTwilio() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/2010-04-01/Accounts/AC123/Messages.json", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            synchronized (received) {
                received.add(URLDecoder.decode(body, StandardCharsets.UTF_8)
                    + "|auth=" + exchange.getRequestHeaders().getFirst("Authorization"));
            }
            byte[] reply = "{\"sid\":\"SM1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private SmsSender twilio() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new SmsSender("auto", "AC123", "secret", "+15005550006", base, "", "");
    }

    @Test
    @DisplayName("with Twilio set up, the code is texted and never returned in the reply")
    void textedNotExposed() {
        OtpService otp = new OtpService(true, null, twilio());
        OtpService.Sent sent = otp.send(Role.RIDER, "9876543210");

        assertTrue(sent.sent());
        assertEquals("sms", sent.delivery());
        assertNull(sent.devCode(), "a texted code must not also be handed back");
        assertEquals(1, received.size());
        String request = received.get(0);
        assertTrue(request.contains("To=+919876543210"), request);
        assertTrue(request.contains("From=+15005550006"), request);
        assertTrue(request.contains("auth=Basic "), request);

        // The code in the message is the one the server accepts.
        Matcher m = Pattern.compile("code is (\\d{6})").matcher(request);
        assertTrue(m.find(), request);
        assertEquals(OtpService.Verdict.OK, otp.verify(Role.RIDER, "9876543210", m.group(1)));
    }

    @Test
    @DisplayName("when the provider refuses, sign-in still works through mock delivery")
    void fallsBackToMock() {
        status = 401;
        OtpService otp = new OtpService(true, null, twilio());
        OtpService.Sent sent = otp.send(Role.RIDER, "9876543211");

        assertEquals("mock", sent.delivery());
        assertNotNull(sent.devCode());
        assertEquals(OtpService.Verdict.OK, otp.verify(Role.RIDER, "9876543211", sent.devCode()));
    }

    @Test
    @DisplayName("with no provider, delivery is mock and the provider reads as none")
    void noProvider() {
        SmsSender none = new SmsSender("auto", "", "", "", "https://api.twilio.com", "", "");
        assertEquals("none", none.provider());
        OtpService.Sent sent = new OtpService(true, null, none).send(Role.DRIVER, "9876543212");
        assertEquals("mock", sent.delivery());
        assertNotNull(sent.devCode());
        assertTrue(SmsSender.messageFor("123456").contains("123456"));
    }
}
