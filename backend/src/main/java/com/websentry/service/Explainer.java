package com.websentry.service;

import com.websentry.model.RiskAssessment;
import com.websentry.model.RuleChecks;

/** Turns the technical findings into words a non-technical person understands. */
public interface Explainer {

    /** @param allowAi false to use the built-in template and skip the API call. */
    Explanation explain(String url, RuleChecks checks, RiskAssessment risk, boolean allowAi);
}
