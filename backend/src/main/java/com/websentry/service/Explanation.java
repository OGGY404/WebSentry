package com.websentry.service;

/**
 * The plain-English text shown to the user.
 *
 * @param text    two or three simple sentences
 * @param fromAi  true if Claude wrote it, false if the built-in template did
 */
public record Explanation(String text, boolean fromAi) {
}
