package com.websentry.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

/** Tests for reading the .env file. */
class DotenvEnvironmentPostProcessorTest {

    private final DotenvEnvironmentPostProcessor processor = new DotenvEnvironmentPostProcessor();

    @TempDir
    Path folder;

    /** Writes the given text to a file and reads it back through the parser. */
    private Map<String, Object> parse(String contents) throws IOException {
        Path file = folder.resolve(".env");
        Files.writeString(file, contents, StandardCharsets.UTF_8);
        return processor.read(file);
    }

    @Test
    @DisplayName("Normal KEY=VALUE lines are read")
    void readsSimpleValues() throws IOException {
        Map<String, Object> values = parse("""
                ANTHROPIC_API_KEY=sk-ant-example
                ANTHROPIC_MODEL=claude-haiku-4-5
                """);

        assertEquals("sk-ant-example", values.get("ANTHROPIC_API_KEY"));
        assertEquals("claude-haiku-4-5", values.get("ANTHROPIC_MODEL"));
    }

    @Test
    @DisplayName("Comments and blank lines are skipped")
    void skipsCommentsAndBlanks() throws IOException {
        Map<String, Object> values = parse("""
                # this is a comment

                ADMIN_USERNAME=admin
                """);

        assertEquals(1, values.size());
        assertEquals("admin", values.get("ADMIN_USERNAME"));
    }

    @Test
    @DisplayName("A blank value is ignored so it cannot hide a real setting")
    void blankValuesAreIgnored() throws IOException {
        // The case that matters.
        Map<String, Object> values = parse("""
                MONGODB_URI=
                ANTHROPIC_API_KEY=sk-ant-example
                """);

        assertFalse(values.containsKey("MONGODB_URI"));
        assertEquals("sk-ant-example", values.get("ANTHROPIC_API_KEY"));
    }

    @Test
    @DisplayName("Quotes around a value are removed")
    void stripsQuotes() throws IOException {
        Map<String, Object> values = parse("""
                ADMIN_PASSWORD="my secret"
                ADMIN_USERNAME='admin'
                """);

        assertEquals("my secret", values.get("ADMIN_PASSWORD"));
        assertEquals("admin", values.get("ADMIN_USERNAME"));
    }

    @Test
    @DisplayName("A value containing an equals sign is kept whole")
    void keepsEqualsInsideValue() throws IOException {
        // Connection strings contain = signs, so only the first one splits.
        Map<String, Object> values = parse("MONGODB_URI=mongodb+srv://a:b@c/d?retryWrites=true\n");

        assertEquals("mongodb+srv://a:b@c/d?retryWrites=true", values.get("MONGODB_URI"));
    }

    @Test
    @DisplayName("A line with no equals sign is skipped")
    void ignoresRubbishLines() throws IOException {
        Map<String, Object> values = parse("""
                this line has no equals sign
                =value with no key
                GOOD_KEY=value
                """);

        assertEquals(1, values.size());
        assertEquals("value", values.get("GOOD_KEY"));
    }

    @Test
    @DisplayName("A missing file gives nothing back instead of failing")
    void missingFileIsHandled() {
        Map<String, Object> values = processor.read(folder.resolve("does-not-exist.env"));

        assertTrue(values.isEmpty());
    }

    @Test
    @DisplayName("Starting with no .env file anywhere does not break the application")
    void startupWithoutDotenvDoesNotThrow() {
        // The backend must start on a computer that has no .env at all.
        assertDoesNotThrow(() ->
                processor.postProcessEnvironment(new MockEnvironment(), null));
    }
}
