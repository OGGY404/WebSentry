package com.websentry.model;

import java.util.List;

/**
 * Result of the rule engine. These are facts measured live from the website,
 * not guesses from the ML model.
 *
 * @param https             true if the address starts with https
 * @param sslValid          true if the certificate was accepted by Java's trust store
 * @param sslIssuer         who signed the certificate, null if we could not read it
 * @param sslDaysToExpiry   days left before the certificate expires, null if unknown
 * @param mixedContent      true if an https page loads http files
 * @param inPhishingDb      true if Google Safe Browsing knows this URL as bad
 * @param suspiciousPattern true if the address looks suspicious by our rules
 * @param reasons           short plain-English notes about what was found
 */
public record RuleChecks(
        boolean https,
        boolean sslValid,
        String sslIssuer,
        Integer sslDaysToExpiry,
        boolean mixedContent,
        boolean inPhishingDb,
        boolean suspiciousPattern,
        List<String> reasons) {
}
