package com.websentry.service;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.websentry.config.WebSentryProperties;

/** Asks Google Safe Browsing whether a URL is already known to be dangerous. */
@Service
public class SafeBrowsingClient {

    private static final Logger log = LoggerFactory.getLogger(SafeBrowsingClient.class);

    private static final String ENDPOINT = "https://safebrowsing.googleapis.com/v4/threatMatches:find";

    private final RestClient client;
    private final WebSentryProperties properties;

    public SafeBrowsingClient(RestClient.Builder builder, WebSentryProperties properties) {
        this.properties = properties;
        this.client = builder.build();
    }

    /** True if Google lists this URL as a threat. */
    @SuppressWarnings("unchecked")
    public boolean isKnownThreat(String url) {
        if (!properties.getSafeBrowsing().isEnabled()) {
            return false;
        }

        long started = System.nanoTime();
        try {
            Map<String, Object> body = Map.of(
                    "client", Map.of("clientId", "websentry", "clientVersion", "1.0.0"),
                    "threatInfo", Map.of(
                            "threatTypes", List.of("MALWARE", "SOCIAL_ENGINEERING",
                                    "UNWANTED_SOFTWARE", "POTENTIALLY_HARMFUL_APPLICATION"),
                            "platformTypes", List.of("ANY_PLATFORM"),
                            "threatEntryTypes", List.of("URL"),
                            "threatEntries", List.of(Map.of("url", url))));

            Map<String, Object> response = client.post()
                    .uri(ENDPOINT + "?key=" + properties.getSafeBrowsing().getApiKey())
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            log.info("safe browsing lookup took {} ms", elapsedMs);

            // An empty response means Google found nothing against this URL.
            return response != null && response.get("matches") instanceof List<?> matches
                    && !matches.isEmpty();

        } catch (Exception e) {
            log.warn("Safe Browsing lookup failed, continuing without it: {}", e.toString());
            return false;
        }
    }
}
