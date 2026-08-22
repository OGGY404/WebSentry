package com.websentry.model;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A short summary row for the dashboard: "most flagged websites".
 * One row per domain, with a counter, so the dashboard does not have to count
 * thousands of scan records every time it loads.
 */
@Document(collection = "flaggedUrls")
public class FlaggedUrl {

    @Id
    private String domain;

    private long hitCount;
    private int lastRiskScore;
    private String lastRiskBand;
    private Instant firstSeen;
    private Instant lastSeen;

    public FlaggedUrl() {
        // Needed by Spring Data.
    }

    public FlaggedUrl(String domain) {
        this.domain = domain;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public long getHitCount() {
        return hitCount;
    }

    public void setHitCount(long hitCount) {
        this.hitCount = hitCount;
    }

    public int getLastRiskScore() {
        return lastRiskScore;
    }

    public void setLastRiskScore(int lastRiskScore) {
        this.lastRiskScore = lastRiskScore;
    }

    public String getLastRiskBand() {
        return lastRiskBand;
    }

    public void setLastRiskBand(String lastRiskBand) {
        this.lastRiskBand = lastRiskBand;
    }

    public Instant getFirstSeen() {
        return firstSeen;
    }

    public void setFirstSeen(Instant firstSeen) {
        this.firstSeen = firstSeen;
    }

    public Instant getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(Instant lastSeen) {
        this.lastSeen = lastSeen;
    }
}
