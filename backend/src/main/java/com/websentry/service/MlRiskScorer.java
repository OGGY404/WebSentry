package com.websentry.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.websentry.config.WebSentryProperties;
import com.websentry.model.RiskAssessment;
import com.websentry.model.TopFeature;

/**
 * Version B scorer. Sends the URL to the Python ML service and reads back the
 * score produced by the trained classifier.
 *
 * If the ML service is not running the scan does not fail. We return a neutral
 * score and mark the source, so the rule checks still decide the outcome.
 */
@Component
public class MlRiskScorer implements RiskScorer {

    private static final Logger log = LoggerFactory.getLogger(MlRiskScorer.class);

    private final RestClient client;
    private final WebSentryProperties properties;

    public MlRiskScorer(RestClient.Builder builder, WebSentryProperties properties) {
        this.properties = properties;
        this.client = builder.baseUrl(properties.getMl().getUrl()).build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public RiskAssessment score(String url) {
        long started = System.nanoTime();
        try {
            Map<String, Object> response = client.post()
                    .uri("/predict")
                    .body(Map.of("url", url))
                    .retrieve()
                    .body(Map.class);

            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            // Latency is logged for the performance section of the evaluation chapter.
            log.info("ml /predict took {} ms for {}", elapsedMs, shorten(url));

            if (response == null) {
                return unavailable("ml-empty-response");
            }

            int riskScore = toInt(response.get("riskScore"));
            String band = String.valueOf(response.getOrDefault("riskBand", RiskAssessment.bandFor(riskScore)));

            return new RiskAssessment(
                    riskScore,
                    band,
                    String.valueOf(response.getOrDefault("recommendation",
                            RiskAssessment.recommendationFor(band))),
                    toDouble(response.get("confidence")),
                    readTopFeatures(response.get("topFeatures")),
                    String.valueOf(response.getOrDefault("modelVersion", "unknown")),
                    "ml");

        } catch (Exception e) {
            log.warn("ML service unavailable at {}: {}", properties.getMl().getUrl(), e.toString());
            return unavailable("ml-unavailable");
        }
    }

    @Override
    public String name() {
        return "ml";
    }

    /**
     * Used when the ML service cannot be reached. A score of 0 means the model
     * adds nothing, so the final score comes from the rule checks alone.
     */
    private RiskAssessment unavailable(String source) {
        return new RiskAssessment(0, "LOW", RiskAssessment.recommendationFor("LOW"),
                0.0, List.of(), "unavailable", source);
    }

    @SuppressWarnings("unchecked")
    private List<TopFeature> readTopFeatures(Object raw) {
        List<TopFeature> features = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    features.add(new TopFeature(
                            String.valueOf(map.get("feature")),
                            String.valueOf(map.get("label")),
                            toDouble(map.get("contribution"))));
                }
            }
        }
        return features;
    }

    private int toInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private double toDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private String shorten(String url) {
        return url.length() <= 80 ? url : url.substring(0, 80) + "...";
    }
}
