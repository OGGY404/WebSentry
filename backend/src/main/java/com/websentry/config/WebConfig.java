package com.websentry.config;

import java.time.Duration;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web setup: who is allowed to call the API, and the HTTP client used to call
 * the ML service and Claude.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * The browser extension calls this API from a chrome-extension:// page, and
     * the React dashboard calls it from localhost. Both need CORS permission.
     *
     * allowedOriginPatterns is used because a plain "*" is rejected once
     * credentials are involved, and extension origins are not fixed strings.
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("chrome-extension://*", "moz-extension://*",
                        "http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }

    /**
     * Gives every RestClient a connect and read timeout. Without this a slow
     * external API could hold a scan open far past the one second target.
     *
     * Spring Boot builds the RestClient.Builder itself and applies this
     * customizer to it, so we must not create a builder bean of our own.
     */
    @Bean
    public RestClientCustomizer restClientTimeouts() {
        return builder -> {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(3));
            factory.setReadTimeout(Duration.ofSeconds(8));
            builder.requestFactory(factory);
        };
    }
}
