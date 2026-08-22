package com.websentry.service;

import com.websentry.model.RiskAssessment;
import com.websentry.model.RuleChecks;

/** Turns the technical findings into words a non-technical person understands. */
public interface Explainer {

    /**
     * @param allowAi false to use the built-in template and skip the API call.
     *        The evaluation run sets this to false: the wording has no effect on
     *        the score being measured, so paying for 400 calls and waiting ten
     *        minutes for text nobody reads would be waste.
     */
    Explanation explain(String url, RuleChecks checks, RiskAssessment risk, boolean allowAi);
}
