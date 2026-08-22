package com.websentry.service;

import com.websentry.model.RiskAssessment;

/**
 * Produces the 0-100 risk score for a URL.
 *
 * There are two versions on purpose, as agreed in the architecture decision:
 *   MlRiskScorer  = Version B, our own trained model gives the score.
 *   LlmRiskScorer = Version A, Claude gives the score.
 *
 * Switching between them is one setting (WEBSENTRY_SCORER), not a code change.
 */
public interface RiskScorer {

    /** Score one URL. Must never throw: return a safe fallback instead. */
    RiskAssessment score(String url);

    /** Short name written into the scan record, so results can be traced. */
    String name();
}
