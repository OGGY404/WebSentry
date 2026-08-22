package com.websentry.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All WebSentry settings in one place. The values come from application.properties,
 * which reads them from environment variables (see .env.example).
 *
 * Keys are never written in code. A blank key means the feature turns itself off
 * instead of breaking the scan.
 */
@ConfigurationProperties(prefix = "websentry")
public class WebSentryProperties {

    /** "ml" = Version B (our model scores, Claude explains). "llm" = Version A. */
    private String scorer = "ml";

    /** How long a scan result is reused for the same domain, in seconds. */
    private int cacheTtlSeconds = 300;

    private final Ml ml = new Ml();
    private final Anthropic anthropic = new Anthropic();
    private final SafeBrowsing safeBrowsing = new SafeBrowsing();
    private final Mongodb mongodb = new Mongodb();
    private final Admin admin = new Admin();

    public String getScorer() {
        return scorer;
    }

    public void setScorer(String scorer) {
        this.scorer = scorer;
    }

    public int getCacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public void setCacheTtlSeconds(int cacheTtlSeconds) {
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    public Ml getMl() {
        return ml;
    }

    public Anthropic getAnthropic() {
        return anthropic;
    }

    public SafeBrowsing getSafeBrowsing() {
        return safeBrowsing;
    }

    public Mongodb getMongodb() {
        return mongodb;
    }

    public Admin getAdmin() {
        return admin;
    }

    /** The internal Python service that scores URLs. */
    public static class Ml {
        private String url = "http://127.0.0.1:8000";
        private int timeoutMs = 1500;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }

    /** Claude API, used only to write the plain-English explanation. */
    public static class Anthropic {
        private String apiKey = "";
        private String model = "claude-haiku-4-5";
        private int maxTokens = 200;
        private int timeoutMs = 6000;

        public boolean isEnabled() {
            return apiKey != null && !apiKey.isBlank();
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }

    /** Google Safe Browsing. Optional on purpose: the scan works without it. */
    public static class SafeBrowsing {
        private String apiKey = "";
        private int timeoutMs = 2000;

        public boolean isEnabled() {
            return apiKey != null && !apiKey.isBlank();
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }

    /** If the address is blank we use the in-memory store instead. */
    public static class Mongodb {
        private String uri = "";

        public boolean isEnabled() {
            return uri != null && !uri.isBlank();
        }

        public String getUri() {
            return uri;
        }

        public void setUri(String uri) {
            this.uri = uri;
        }
    }

    /** One hardcoded admin for the dashboard, as agreed in the reduced scope. */
    public static class Admin {
        private String username = "admin";
        private String password = "change-me-before-demo";

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}
