package com.websentry.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.websentry.config.WebSentryProperties;
import com.websentry.model.RiskAssessment;
import com.websentry.model.RuleChecks;
import com.websentry.model.TopFeature;

/** Writes the explanation the user reads in the popup. */
@Service
public class ClaudeExplainer implements Explainer {

    private static final Logger log = LoggerFactory.getLogger(ClaudeExplainer.class);

    private static final String SYSTEM_PROMPT = """
            You write short website safety notes for ordinary people who are not
            technical. You are given the security findings for one website and a
            risk score that has already been decided by a separate system.

            Rules you must follow:
            - Write two or three short sentences. Nothing else.
            - Use simple everyday English. No technical words, no jargon.
            - Explain only the findings you are given. Never invent a finding.
            - Never disagree with the risk score or the recommendation you are given.
            - Do not greet the user and do not add a title.
            - Speak plainly about what this means for the person, and what to do.
            """;

    /** The same wording is reused for the same site and findings, so we do not pay twice. */
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private static final int MAX_CACHE_ENTRIES = 2000;

    private final WebSentryProperties properties;
    private final AnthropicClient client;

    public ClaudeExplainer(WebSentryProperties properties) {
        this.properties = properties;
        this.client = buildClient(properties);
    }

    private AnthropicClient buildClient(WebSentryProperties properties) {
        if (!properties.getAnthropic().isEnabled()) {
            log.warn("ANTHROPIC_API_KEY is not set. Explanations will use the built-in template.");
            return null;
        }
        try {
            return AnthropicOkHttpClient.builder()
                    .apiKey(properties.getAnthropic().getApiKey())
                    .build();
        } catch (Exception e) {
            log.error("Could not start the Anthropic client: {}", e.toString());
            return null;
        }
    }

    @Override
    public Explanation explain(String url, RuleChecks checks, RiskAssessment risk, boolean allowAi) {
        if (!allowAi) {
            return new Explanation(template(checks, risk), false);
        }

        String cacheKey = cacheKey(url, checks, risk);
        String cached = cache.get(cacheKey);
        if (cached != null) {
            return new Explanation(cached, true);
        }

        if (client == null) {
            return new Explanation(template(checks, risk), false);
        }

        long started = System.nanoTime();
        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(properties.getAnthropic().getModel())
                    .maxTokens(properties.getAnthropic().getMaxTokens())
                    .system(SYSTEM_PROMPT)
                    .addUserMessage(buildFindings(url, checks, risk))
                    .build();

            Message response = client.messages().create(params);

            String text = response.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(block -> block.text())
                    .reduce("", (a, b) -> a.isBlank() ? b : a + " " + b)
                    .trim();

            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            log.info("claude explanation took {} ms ({} input, {} output tokens)",
                    elapsedMs,
                    response.usage().inputTokens(),
                    response.usage().outputTokens());

            if (text.isBlank()) {
                return new Explanation(template(checks, risk), false);
            }

            if (cache.size() < MAX_CACHE_ENTRIES) {
                cache.put(cacheKey, text);
            }
            return new Explanation(text, true);

        } catch (Exception e) {
            // Any failure at all falls back to the template.
            log.warn("Claude explanation failed, using template instead: {}", e.toString());
            return new Explanation(template(checks, risk), false);
        }
    }

    /** Builds the list of findings that is sent to the model. */
    private String buildFindings(String url, RuleChecks checks, RiskAssessment risk) {
        StringBuilder text = new StringBuilder();
        text.append("Website: ").append(url).append("\n");
        text.append("Risk score: ").append(risk.riskScore()).append(" out of 100 (")
                .append(risk.riskBand()).append(" risk)\n");
        text.append("Recommendation already decided: ").append(risk.recommendation()).append("\n\n");

        text.append("Findings:\n");
        text.append("- Encrypted connection (HTTPS): ").append(checks.https() ? "yes" : "no").append("\n");
        if (checks.https()) {
            text.append("- Security certificate trusted: ").append(checks.sslValid() ? "yes" : "no").append("\n");
            if (checks.sslDaysToExpiry() != null) {
                text.append("- Certificate expires in ").append(checks.sslDaysToExpiry()).append(" days\n");
            }
            text.append("- Loads unencrypted files inside a secure page: ")
                    .append(checks.mixedContent() ? "yes" : "no").append("\n");
        }
        text.append("- Listed as dangerous by Google Safe Browsing: ")
                .append(checks.inPhishingDb() ? "yes" : "no").append("\n");

        if (checks.reasons() != null && !checks.reasons().isEmpty()) {
            text.append("\nDetails:\n");
            for (String reason : checks.reasons()) {
                text.append("- ").append(reason).append("\n");
            }
        }

        List<TopFeature> features = risk.topFeatures();
        if (features != null && !features.isEmpty()) {
            text.append("\nWhat the detection model noticed about the address:\n");
            for (TopFeature feature : features) {
                text.append("- ").append(feature.label()).append("\n");
            }
        }

        return text.toString();
    }

    /** The built-in fallback. */
    String template(RuleChecks checks, RiskAssessment risk) {
        List<String> problems = new ArrayList<>();
        if (checks.inPhishingDb()) {
            problems.add("it is on a list of known dangerous websites");
        }
        if (!checks.https()) {
            problems.add("the connection is not encrypted");
        } else if (!checks.sslValid()) {
            problems.add("its security certificate could not be trusted");
        }
        if (checks.mixedContent()) {
            problems.add("it loads some files over an unsafe connection");
        }
        if (checks.suspiciousPattern()) {
            problems.add("its web address follows a pattern often used by fake websites");
        }

        boolean lowRisk = "LOW".equals(risk.riskBand());
        StringBuilder text = new StringBuilder();

        // The wording must always agree with the score.
        if (lowRisk && problems.isEmpty()) {
            text.append("We did not find any security problems with this website. ")
                    .append("The connection is encrypted and the address looks normal. ");
        } else if (lowRisk) {
            text.append("This website passed the main safety checks, with one small note: ")
                    .append(joinWithAnd(problems)).append(". ");
        } else if (problems.isEmpty()) {
            // The rule checks passed, so the score came from the model alone.
            text.append("This website was given a risk score of ").append(risk.riskScore())
                    .append(" out of 100. The basic safety checks passed, but its web address ")
                    .append("is unusual compared with normal websites. ");
        } else {
            text.append("This website was given a risk score of ").append(risk.riskScore())
                    .append(" out of 100 because ").append(joinWithAnd(problems)).append(". ");
        }

        switch (risk.riskBand()) {
            case "HIGH" -> text.append("Please leave this website and do not enter any personal details.");
            case "MEDIUM" -> text.append("Be careful here, and avoid typing passwords or card numbers.");
            default -> text.append("It looks safe to continue, but always check the address before you log in.");
        }
        return text.toString();
    }

    /** Joins a list into readable English: "a, b and c". */
    private String joinWithAnd(List<String> items) {
        if (items.size() == 1) {
            return items.get(0);
        }
        String head = String.join(", ", items.subList(0, items.size() - 1));
        return head + " and " + items.get(items.size() - 1);
    }

    /** Same site, same band and same findings means the saved wording can be reused. */
    private String cacheKey(String url, RuleChecks checks, RiskAssessment risk) {
        String domain = UrlUtils.domainOf(url);
        int checksHash = (checks.https() ? 1 : 0)
                + (checks.sslValid() ? 2 : 0)
                + (checks.mixedContent() ? 4 : 0)
                + (checks.inPhishingDb() ? 8 : 0)
                + (checks.suspiciousPattern() ? 16 : 0);
        return domain + "|" + risk.riskBand() + "|" + checksHash;
    }
}
