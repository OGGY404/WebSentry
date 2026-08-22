package com.websentry.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.websentry.model.RiskAssessment;
import com.websentry.model.RuleChecks;

/**
 * Puts the model score and the rule findings together into one final score.
 *
 * Why this exists: the trained model only reads the website name. Facts such as
 * "there is no encryption" or "Google already lists this as dangerous" are
 * measured live and are not part of the model. This class states exactly how
 * those two sources are joined, so the number can be explained and repeated.
 *
 * The rules can only push the score UP, never down. A safe looking address with
 * a broken certificate should not be called safe, but a bad address is never
 * excused just because it has a valid certificate.
 *
 * These weights are written here in one place on purpose. The evaluation script
 * uses the same backend endpoint, so the measured accuracy is the accuracy of
 * this exact combination, not of the model on its own.
 */
@Service
public class RiskCombiner {

    /** Google Safe Browsing is treated as certain, so it sets the score directly. */
    private static final int SAFE_BROWSING_SCORE = 100;

    private static final int NO_HTTPS_PENALTY = 20;
    private static final int BAD_CERTIFICATE_PENALTY = 20;
    private static final int MIXED_CONTENT_PENALTY = 10;
    private static final int SUSPICIOUS_PATTERN_PENALTY = 8;

    /**
     * @param base   the score from the scoring engine (0-100)
     * @param checks what the rule engine measured
     */
    public RiskAssessment combine(RiskAssessment base, RuleChecks checks) {
        int score = base.riskScore();

        if (checks.inPhishingDb()) {
            // A confirmed listing outranks everything else.
            score = SAFE_BROWSING_SCORE;
        } else {
            if (!checks.https()) {
                score += NO_HTTPS_PENALTY;
            } else if (!checks.sslValid()) {
                score += BAD_CERTIFICATE_PENALTY;
            }
            if (checks.mixedContent()) {
                score += MIXED_CONTENT_PENALTY;
            }
            if (checks.suspiciousPattern()) {
                score += SUSPICIOUS_PATTERN_PENALTY;
            }
        }

        score = Math.min(100, Math.max(0, score));
        String band = RiskAssessment.bandFor(score);

        return new RiskAssessment(
                score,
                band,
                RiskAssessment.recommendationFor(band),
                base.confidence(),
                base.topFeatures() == null ? List.of() : base.topFeatures(),
                base.modelVersion(),
                base.source());
    }

    /**
     * Lists which rules changed the score. Used by the dashboard and by the
     * report so the adjustment is visible, not hidden.
     */
    public List<String> appliedAdjustments(RuleChecks checks) {
        List<String> applied = new ArrayList<>();
        if (checks.inPhishingDb()) {
            applied.add("Safe Browsing listing: score set to " + SAFE_BROWSING_SCORE);
            return applied;
        }
        if (!checks.https()) {
            applied.add("No HTTPS: +" + NO_HTTPS_PENALTY);
        } else if (!checks.sslValid()) {
            applied.add("Certificate not trusted: +" + BAD_CERTIFICATE_PENALTY);
        }
        if (checks.mixedContent()) {
            applied.add("Mixed content: +" + MIXED_CONTENT_PENALTY);
        }
        if (checks.suspiciousPattern()) {
            applied.add("Suspicious address pattern: +" + SUSPICIOUS_PATTERN_PENALTY);
        }
        return applied;
    }
}
