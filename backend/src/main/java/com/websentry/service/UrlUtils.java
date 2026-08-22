package com.websentry.service;

import java.net.URI;
import java.util.Locale;

/** Small helpers for working with web addresses. */
public final class UrlUtils {

    private UrlUtils() {
        // Utility class, never created.
    }

    /**
     * Adds https:// when the user typed a bare address like "example.com",
     * so the rest of the code always sees a complete URL.
     */
    public static String normalise(String url) {
        String trimmed = url == null ? "" : url.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        if (!trimmed.contains("://")) {
            return "https://" + trimmed;
        }
        return trimmed;
    }

    /** The website name, for example "example.com". Empty if it cannot be read. */
    public static String domainOf(String url) {
        try {
            String host = URI.create(normalise(url)).getHost();
            if (host == null) {
                return "";
            }
            host = host.toLowerCase(Locale.ROOT);
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return "";
        }
    }
}
