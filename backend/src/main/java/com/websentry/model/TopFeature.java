package com.websentry.model;

/** One feature that pushed the URL towards a phishing verdict. */
public record TopFeature(String feature, String label, double contribution) {
}
