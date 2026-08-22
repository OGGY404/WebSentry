package com.websentry.model;

/**
 * One feature that pushed the URL towards a phishing verdict.
 * The ML service works out these values and gives us a ready-made label.
 */
public record TopFeature(String feature, String label, double contribution) {
}
