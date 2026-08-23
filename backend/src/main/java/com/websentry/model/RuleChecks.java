package com.websentry.model;

import java.util.List;

/** Result of the rule engine. */
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
