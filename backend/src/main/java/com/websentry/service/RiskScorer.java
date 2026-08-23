package com.websentry.service;

import com.websentry.model.RiskAssessment;

/** Produces the 0-100 risk score for a URL. */
public interface RiskScorer {

    /** Score one URL. */
    RiskAssessment score(String url);

    /** Short name written into the scan record, so results can be traced. */
    String name();
}
