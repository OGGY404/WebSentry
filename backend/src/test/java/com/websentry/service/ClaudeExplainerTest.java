package com.websentry.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.websentry.config.WebSentryProperties;
import com.websentry.model.RiskAssessment;
import com.websentry.model.RuleChecks;

/**
 * Tests for the built-in template explanation.
 *
 * The template is what users read whenever the Claude API is switched off or
 * does not answer, so it has to make sense on its own. The important rule is
 * that the words must never disagree with the score: telling someone a site is
 * safe while listing reasons it is dangerous, or the other way round, makes the
 * whole report untrustworthy.
 *
 * No API key is set in these tests, so no network call is made.
 */
class ClaudeExplainerTest {

    private final ClaudeExplainer explainer = new ClaudeExplainer(new WebSentryProperties());

    private RuleChecks clean() {
        return new RuleChecks(true, true, "Test CA", 200, false, false, false, List.of());
    }

    private RuleChecks suspiciousOnly() {
        return new RuleChecks(true, true, "Test CA", 200, false, false, true, List.of());
    }

    private RuleChecks dangerous() {
        return new RuleChecks(false, false, null, null, false, true, true, List.of());
    }

    private RiskAssessment risk(int score) {
        String band = RiskAssessment.bandFor(score);
        return new RiskAssessment(score, band, RiskAssessment.recommendationFor(band),
                0.9, List.of(), "1.0.0", "ml");
    }

    @Test
    @DisplayName("A safe website with nothing wrong is described as safe")
    void safeSiteReadsAsSafe() {
        String text = explainer.template(clean(), risk(5));

        assertTrue(text.contains("did not find any security problems"));
        assertTrue(text.contains("safe to continue"));
    }

    @Test
    @DisplayName("A low score never claims the address looks like a fake website")
    void lowScoreDoesNotContradictItself() {
        // This is the real bug this test protects against: a site scored 8 that
        // was described as following a pattern used by fake websites.
        String text = explainer.template(suspiciousOnly(), risk(8));

        assertTrue(text.contains("passed the main safety checks"));
        assertTrue(text.contains("safe to continue"));
        assertFalse(text.contains("was given a risk score of 8 out of 100 because"));
    }

    @Test
    @DisplayName("A warning score never claims there were no problems")
    void warningScoreDoesNotClaimEverythingIsFine() {
        // The other half of the same bug: a site scored 69 that was described
        // as having no clear security problems.
        String text = explainer.template(clean(), risk(69));

        assertFalse(text.contains("did not find any security problems"));
        assertTrue(text.contains("unusual compared with normal websites"));
        assertTrue(text.contains("Be careful here"));
    }

    @Test
    @DisplayName("A dangerous website is told to be left")
    void dangerousSiteSaysLeave() {
        String text = explainer.template(dangerous(), risk(100));

        assertTrue(text.contains("known dangerous websites"));
        assertTrue(text.contains("not encrypted"));
        assertTrue(text.contains("leave this website"));
    }

    @Test
    @DisplayName("Every band produces advice that matches it")
    void adviceAlwaysMatchesTheBand() {
        assertTrue(explainer.template(clean(), risk(10)).contains("safe to continue"));
        assertTrue(explainer.template(clean(), risk(50)).contains("Be careful here"));
        assertTrue(explainer.template(clean(), risk(90)).contains("leave this website"));
    }

    @Test
    @DisplayName("The explanation stays short enough to read in a popup")
    void explanationStaysShort() {
        String text = explainer.template(dangerous(), risk(100));

        // Two or three sentences. A long wall of text is not read by anyone.
        assertTrue(text.length() < 400, "explanation was " + text.length() + " characters");
    }
}
