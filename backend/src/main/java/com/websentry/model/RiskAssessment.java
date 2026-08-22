package com.websentry.model;

import java.util.List;

/**
 * The risk score for one URL.
 *
 * Score runs 0 to 100 where 0 is safe and 100 is dangerous.
 * Bands: LOW 0-39, MEDIUM 40-69, HIGH 70-100.
 *
 * @param source which engine produced this, "ml" or "llm" or "rules-only"
 */
public record RiskAssessment(
        int riskScore,
        String riskBand,
        String recommendation,
        double confidence,
        List<TopFeature> topFeatures,
        String modelVersion,
        String source) {

    public static final int BAND_LOW_MAX = 39;
    public static final int BAND_MEDIUM_MAX = 69;

    /** Turn a 0-100 score into LOW, MEDIUM or HIGH. */
    public static String bandFor(int score) {
        if (score <= BAND_LOW_MAX) {
            return "LOW";
        }
        if (score <= BAND_MEDIUM_MAX) {
            return "MEDIUM";
        }
        return "HIGH";
    }

    /** The wording the user sees for each band. */
    public static String recommendationFor(String band) {
        return switch (band) {
            case "LOW" -> "Stay Safe";
            case "MEDIUM" -> "Proceed with Caution";
            default -> "Leave Immediately";
        };
    }
}
