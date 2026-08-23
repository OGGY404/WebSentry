package com.websentry.model;

import java.time.Instant;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** One saved scan. */
@Document(collection = "scans")
public class ScanRecord {

    @Id
    private String id;

    private String url;

    @Indexed
    private String domain;

    @Indexed
    private Instant timestamp;

    private int riskScore;
    private String riskBand;
    private boolean isPhishing;

    private RuleChecks checks;
    private List<TopFeature> topFeatures;
    private double mlConfidence;
    private String modelVersion;
    private String scorerSource;

    private String aiExplanation;
    /** true when Claude wrote it, false when the template fallback wrote it. */
    private boolean explanationFromAi;

    private String recommendation;
    private long scanDurationMs;

    @Indexed
    private String clientId;

    public ScanRecord() {
        // Needed by Spring Data when it reads a document back from MongoDB.
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public int getRiskScore() {
        return riskScore;
    }

    public void setRiskScore(int riskScore) {
        this.riskScore = riskScore;
    }

    public String getRiskBand() {
        return riskBand;
    }

    public void setRiskBand(String riskBand) {
        this.riskBand = riskBand;
    }

    public boolean isPhishing() {
        return isPhishing;
    }

    public void setPhishing(boolean phishing) {
        this.isPhishing = phishing;
    }

    public RuleChecks getChecks() {
        return checks;
    }

    public void setChecks(RuleChecks checks) {
        this.checks = checks;
    }

    public List<TopFeature> getTopFeatures() {
        return topFeatures;
    }

    public void setTopFeatures(List<TopFeature> topFeatures) {
        this.topFeatures = topFeatures;
    }

    public double getMlConfidence() {
        return mlConfidence;
    }

    public void setMlConfidence(double mlConfidence) {
        this.mlConfidence = mlConfidence;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public void setModelVersion(String modelVersion) {
        this.modelVersion = modelVersion;
    }

    public String getScorerSource() {
        return scorerSource;
    }

    public void setScorerSource(String scorerSource) {
        this.scorerSource = scorerSource;
    }

    public String getAiExplanation() {
        return aiExplanation;
    }

    public void setAiExplanation(String aiExplanation) {
        this.aiExplanation = aiExplanation;
    }

    public boolean isExplanationFromAi() {
        return explanationFromAi;
    }

    public void setExplanationFromAi(boolean explanationFromAi) {
        this.explanationFromAi = explanationFromAi;
    }

    public String getRecommendation() {
        return recommendation;
    }

    public void setRecommendation(String recommendation) {
        this.recommendation = recommendation;
    }

    public long getScanDurationMs() {
        return scanDurationMs;
    }

    public void setScanDurationMs(long scanDurationMs) {
        this.scanDurationMs = scanDurationMs;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }
}
