package com.websentry.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.websentry.model.RuleChecks;

/** Tests for the rule engine. */
class RuleEngineTest {

    private final RuleEngine engine = new RuleEngine();

    private RuleChecks check(String url) {
        return engine.check(url, false, false);
    }

    @Test
    @DisplayName("A normal https address is not marked suspicious")
    void normalSiteIsClean() {
        RuleChecks checks = check("https://www.google.com");

        assertTrue(checks.https());
        assertFalse(checks.suspiciousPattern());
        assertFalse(checks.inPhishingDb());
    }

    @Test
    @DisplayName("A plain http address is reported as not encrypted")
    void httpIsFlagged() {
        RuleChecks checks = check("http://example.com");

        assertFalse(checks.https());
        assertTrue(checks.reasons().stream().anyMatch(r -> r.contains("not encrypted")));
    }

    @Test
    @DisplayName("An IP address used as the website name is suspicious")
    void ipHostIsSuspicious() {
        RuleChecks checks = check("http://192.168.10.5/login");

        assertTrue(checks.suspiciousPattern());
        assertTrue(checks.reasons().stream().anyMatch(r -> r.contains("raw IP")));
    }

    @Test
    @DisplayName("A brand name outside the real domain is suspicious")
    void brandOutsideDomainIsSuspicious() {
        RuleChecks checks = check("https://paypal.secure-login.xyz/verify");

        assertTrue(checks.suspiciousPattern());
        assertTrue(checks.reasons().stream().anyMatch(r -> r.contains("paypal")));
    }

    @Test
    @DisplayName("The real PayPal address is not flagged for using its own name")
    void realBrandDomainIsNotFlaggedForItsName() {
        RuleChecks checks = check("https://www.paypal.com/uk/home");

        assertFalse(checks.reasons().stream()
                .anyMatch(r -> r.contains("mentions paypal")));
    }

    @Test
    @DisplayName("An often abused ending such as .tk is suspicious")
    void abusedTldIsSuspicious() {
        RuleChecks checks = check("http://free-prize.tk");

        assertTrue(checks.suspiciousPattern());
    }

    @Test
    @DisplayName("An @ sign in the address is suspicious")
    void atSignIsSuspicious() {
        RuleChecks checks = check("http://google.com@evil.example/login");

        assertTrue(checks.suspiciousPattern());
    }

    @Test
    @DisplayName("Look-alike foreign characters are suspicious")
    void punycodeIsSuspicious() {
        RuleChecks checks = check("https://xn--pypal-4ve.com");

        assertTrue(checks.suspiciousPattern());
    }

    @Test
    @DisplayName("Skipping live checks does not claim the certificate is broken")
    void skippingLiveChecksDoesNotFailTheCertificate() {
        RuleChecks checks = check("https://www.bbc.co.uk");

        assertTrue(checks.https());
        // We never opened the site, so we must not say the certificate is bad.
        assertTrue(checks.sslValid());
    }

    @Test
    @DisplayName("A word like verify in the address is picked up")
    void suspiciousKeywordIsFound() {
        RuleChecks checks = check("https://account-verify-now.example.com");

        assertTrue(checks.suspiciousPattern());
    }

    @Test
    @DisplayName("A Safe Browsing hit is carried through to the result")
    void safeBrowsingHitIsRecorded() {
        RuleChecks checks = engine.check("https://example.com", true, false);

        assertTrue(checks.inPhishingDb());
        assertTrue(checks.reasons().stream().anyMatch(r -> r.contains("Safe Browsing")));
    }

    @Test
    @DisplayName("Text that is not a web address does not crash the engine")
    void rubbishInputIsHandled() {
        RuleChecks checks = check("not a url at all");

        assertFalse(checks.https());
        assertEquals(false, checks.inPhishingDb());
    }
}
