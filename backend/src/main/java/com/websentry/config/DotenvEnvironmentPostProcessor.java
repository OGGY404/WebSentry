package com.websentry.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** Reads the .env file, so keys do not have to be typed into the terminal. */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    /** Where to look for the file, in order. */
    private static final List<String> SEARCH_PATHS = List.of(".env", "../.env", "../../.env");

    private static final String SOURCE_NAME = "websentryDotenv";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        for (String candidate : SEARCH_PATHS) {
            Path path = Path.of(candidate).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                continue;
            }

            Map<String, Object> values = read(path);
            if (values.isEmpty()) {
                return;
            }

            // A real environment variable wins. The file only fills in what is missing.
            environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, values));

            // Names only.
            System.out.println("Loaded " + values.size() + " settings from " + path
                    + " " + values.keySet());
            return;
        }
    }

    /** Reads simple KEY=VALUE lines. */
    Map<String, Object> read(Path path) {
        Map<String, Object> values = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }

                int equals = trimmed.indexOf('=');
                if (equals <= 0) {
                    continue;
                }

                String key = trimmed.substring(0, equals).trim();
                String value = trimmed.substring(equals + 1).trim();

                // Allow quotes around a value, for example KEY="some value".
                if (value.length() >= 2
                        && ((value.startsWith("\"") && value.endsWith("\""))
                            || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }

                // A blank value means "not set", so it is skipped.
                if (!value.isEmpty()) {
                    values.put(key, value);
                }
            }
        } catch (IOException e) {
            System.out.println("Could not read " + path + ": " + e.getMessage());
        }
        return values;
    }
}
