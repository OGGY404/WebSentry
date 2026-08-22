package com.websentry.service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.websentry.config.WebSentryProperties;
import com.websentry.model.RiskAssessment;

/**
 * Version A scorer: Claude decides the score as well as the wording.
 *
 * This is kept so the original proposal architecture can still be demonstrated
 * and compared. Version B (MlRiskScorer) is the committed design because the
 * accuracy figure has to be something the project owns and can defend.
 *
 * Switch with WEBSENTRY_SCORER=llm in the .env file.
 */
@Component
public class LlmRiskScorer implements RiskScorer {

    private static final Logger log = LoggerFactory.getLogger(LlmRiskScorer.class);

    private static final Pattern FIRST_NUMBER = Pattern.compile("\\d{1,3}");

    private static final String SYSTEM_PROMPT = """
            You rate how likely a web address is to be a phishing or scam website.
            Look only at the address itself.
            Answer with one whole number from 0 to 100 and nothing else.
            0 means certainly safe. 100 means certainly a scam.
            """;

    private final WebSentryProperties properties;
    private final AnthropicClient client;

    public LlmRiskScorer(WebSentryProperties properties) {
        this.properties = properties;
        this.client = properties.getAnthropic().isEnabled()
                ? AnthropicOkHttpClient.builder().apiKey(properties.getAnthropic().getApiKey()).build()
                : null;
    }

    @Override
    public RiskAssessment score(String url) {
        if (client == null) {
            log.warn("Version A scorer selected but no Anthropic key is set. Returning a neutral score.");
            return neutral("llm-unavailable");
        }

        long started = System.nanoTime();
        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(properties.getAnthropic().getModel())
                    .maxTokens(16L)
                    .system(SYSTEM_PROMPT)
                    .addUserMessage(url)
                    .build();

            Message response = client.messages().create(params);
            String text = response.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(block -> block.text())
                    .findFirst()
                    .orElse("");

            log.info("llm scoring took {} ms", (System.nanoTime() - started) / 1_000_000);

            Matcher matcher = FIRST_NUMBER.matcher(text);
            if (!matcher.find()) {
                return neutral("llm-unparsed");
            }
            int riskScore = Math.min(100, Math.max(0, Integer.parseInt(matcher.group())));
            String band = RiskAssessment.bandFor(riskScore);

            return new RiskAssessment(riskScore, band, RiskAssessment.recommendationFor(band),
                    0.0, List.of(), properties.getAnthropic().getModel(), "llm");

        } catch (Exception e) {
            log.warn("Version A scoring failed: {}", e.toString());
            return neutral("llm-error");
        }
    }

    @Override
    public String name() {
        return "llm";
    }

    private RiskAssessment neutral(String source) {
        return new RiskAssessment(0, "LOW", RiskAssessment.recommendationFor("LOW"),
                0.0, List.of(), "unavailable", source);
    }
}
