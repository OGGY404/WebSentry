package com.websentry.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpServer;
import com.websentry.config.WebSentryProperties;

/**
 * Tests the Google Safe Browsing lookup.
 *
 * A small web server stands in for Google, so the request and the reading of
 * the reply can be checked without a key and without the internet. This class
 * had never been run before, because no key has been configured.
 *
 * The behaviour that matters most is that a failure here never breaks a scan.
 * The lookup is optional by design, so anything unexpected must come back as
 * "not a threat" rather than as an error.
 */
class SafeBrowsingClientTest {

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> lastBody = new AtomicReference<>("");

    /** Starts a stand in server that always replies with the given text. */
    private String startServer(int status, String reply) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/threatMatches", exchange -> {
            calls.incrementAndGet();
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(out);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/threatMatches";
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private SafeBrowsingClient clientWithKey(String endpoint) {
        WebSentryProperties properties = new WebSentryProperties();
        properties.getSafeBrowsing().setApiKey("test-key");
        return new SafeBrowsingClient(RestClient.builder(), properties, endpoint);
    }

    @Test
    @DisplayName("With no key set, nothing is sent and the answer is not a threat")
    void noKeyMeansNoCall() throws IOException {
        String endpoint = startServer(200, "{}");
        WebSentryProperties properties = new WebSentryProperties();

        SafeBrowsingClient client =
                new SafeBrowsingClient(RestClient.builder(), properties, endpoint);

        assertFalse(client.isKnownThreat("http://example.com"));
        assertEquals(0, calls.get(), "no request should be made without a key");
    }

    @Test
    @DisplayName("An empty reply means the address is not listed")
    void emptyReplyMeansSafe() throws IOException {
        SafeBrowsingClient client = clientWithKey(startServer(200, "{}"));

        assertFalse(client.isKnownThreat("http://example.com"));
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("A reply containing matches means the address is listed")
    void matchesMeansThreat() throws IOException {
        String reply = """
                {"matches":[{"threatType":"SOCIAL_ENGINEERING",
                 "platformType":"ANY_PLATFORM",
                 "threat":{"url":"http://bad.example"}}]}
                """;
        SafeBrowsingClient client = clientWithKey(startServer(200, reply));

        assertTrue(client.isKnownThreat("http://bad.example"));
    }

    @Test
    @DisplayName("An empty matches list still means not listed")
    void emptyMatchesListMeansSafe() throws IOException {
        SafeBrowsingClient client = clientWithKey(startServer(200, "{\"matches\":[]}"));

        assertFalse(client.isKnownThreat("http://example.com"));
    }

    @Test
    @DisplayName("The address being checked is actually sent to Google")
    void sendsTheAddress() throws IOException {
        SafeBrowsingClient client = clientWithKey(startServer(200, "{}"));

        client.isKnownThreat("http://check-me.example/login");

        assertTrue(lastBody.get().contains("http://check-me.example/login"));
        assertTrue(lastBody.get().contains("SOCIAL_ENGINEERING"),
                "the phishing threat type must be requested");
    }

    @Test
    @DisplayName("A refused key does not break the scan")
    void errorStatusIsHandled() throws IOException {
        SafeBrowsingClient client = clientWithKey(startServer(403, "{\"error\":\"bad key\"}"));

        // A wrong or expired key must not stop a scan. It only means this one
        // check is skipped.
        assertFalse(client.isKnownThreat("http://example.com"));
    }

    @Test
    @DisplayName("Nonsense in the reply does not break the scan")
    void rubbishReplyIsHandled() throws IOException {
        SafeBrowsingClient client = clientWithKey(startServer(200, "not json at all"));

        assertFalse(client.isKnownThreat("http://example.com"));
    }

    @Test
    @DisplayName("A server that cannot be reached does not break the scan")
    void unreachableServerIsHandled() {
        SafeBrowsingClient client = clientWithKey("http://127.0.0.1:1/threatMatches");

        assertFalse(client.isKnownThreat("http://example.com"));
    }
}
