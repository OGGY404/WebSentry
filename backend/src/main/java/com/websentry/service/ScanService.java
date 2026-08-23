package com.websentry.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.websentry.config.WebSentryProperties;
import com.websentry.model.RiskAssessment;
import com.websentry.model.RuleChecks;
import com.websentry.model.ScanRecord;
import com.websentry.repository.ScanStore;

/** Runs one scan: threat lookup, rule checks, score, combine, explain, save. */
@Service
public class ScanService {

    private static final Logger log = LoggerFactory.getLogger(ScanService.class);

    private final RuleEngine ruleEngine;
    private final SafeBrowsingClient safeBrowsing;
    private final RiskScorer scorer;
    private final RiskCombiner combiner;
    private final Explainer explainer;
    private final ScanStore store;
    private final WebSentryProperties properties;

    private final Map<String, CachedScan> cache = new ConcurrentHashMap<>();

    public ScanService(RuleEngine ruleEngine,
                       SafeBrowsingClient safeBrowsing,
                       List<RiskScorer> scorers,
                       RiskCombiner combiner,
                       Explainer explainer,
                       ScanStore store,
                       WebSentryProperties properties) {
        this.ruleEngine = ruleEngine;
        this.safeBrowsing = safeBrowsing;
        this.combiner = combiner;
        this.explainer = explainer;
        this.store = store;
        this.properties = properties;

        // Pick the engine named in the settings.
        String wanted = properties.getScorer();
        this.scorer = scorers.stream()
                .filter(candidate -> candidate.name().equalsIgnoreCase(wanted))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No scoring engine called \"" + wanted + "\". Use ml or llm."));
        log.info("Scoring engine in use: {}", this.scorer.name());
    }

    /** Scan one URL with the live website checks turned on. */
    public ScanRecord scan(String rawUrl, String clientId) {
        return scan(rawUrl, clientId, true);
    }

    /** Scan one URL. */
    public ScanRecord scan(String rawUrl, String clientId, boolean performLiveChecks) {
        long started = System.nanoTime();
        String url = UrlUtils.normalise(rawUrl);
        String domain = UrlUtils.domainOf(url);

        // Results are reused briefly, so moving around one website does not scan it again.
        if (performLiveChecks) {
            CachedScan cached = cache.get(domain);
            if (cached != null && !cached.isOlderThan(properties.getCacheTtlSeconds())) {
                log.debug("cache hit for {}", domain);
                return cached.record();
            }
        }

        boolean safeBrowsingHit = performLiveChecks && safeBrowsing.isKnownThreat(url);
        RuleChecks checks = ruleEngine.check(url, safeBrowsingHit, performLiveChecks);
        RiskAssessment base = scorer.score(url);
        RiskAssessment finalRisk = combiner.combine(base, checks);
        // Evaluation runs use the template.
        Explanation explanation = explainer.explain(url, checks, finalRisk, performLiveChecks);

        long durationMs = (System.nanoTime() - started) / 1_000_000;

        ScanRecord record = new ScanRecord();
        record.setUrl(url);
        record.setDomain(domain);
        record.setTimestamp(Instant.now());
        record.setRiskScore(finalRisk.riskScore());
        record.setRiskBand(finalRisk.riskBand());
        // "Phishing" for reporting means the system told the user to leave.
        record.setPhishing("HIGH".equals(finalRisk.riskBand()));
        record.setChecks(checks);
        record.setTopFeatures(finalRisk.topFeatures());
        record.setMlConfidence(finalRisk.confidence());
        record.setModelVersion(finalRisk.modelVersion());
        record.setScorerSource(finalRisk.source());
        record.setAiExplanation(explanation.text());
        record.setExplanationFromAi(explanation.fromAi());
        record.setRecommendation(finalRisk.recommendation());
        record.setScanDurationMs(durationMs);
        record.setClientId(clientId);

        try {
            store.save(record);
            if (!"LOW".equals(record.getRiskBand()) && !domain.isBlank()) {
                store.recordFlagged(record);
            }
        } catch (Exception e) {
            // Saving must never break the user's scan.
            log.error("Could not save the scan for {}: {}", domain, e.toString());
        }

        if (performLiveChecks) {
            cache.put(domain, new CachedScan(record, Instant.now()));
        }
        log.info("scanned {} -> {} ({}) in {} ms", domain, record.getRiskScore(),
                record.getRiskBand(), durationMs);
        return record;
    }

    public Optional<ScanRecord> findById(String id) {
        return store.findById(id);
    }

    public List<ScanRecord> history(String clientId, int limit) {
        return store.findRecentByClient(clientId, limit);
    }

    /** Which engine is running, shown on the health endpoint. */
    public String activeScorer() {
        return scorer.name();
    }

    /** One saved result plus the time it was made. */
    private record CachedScan(ScanRecord record, Instant savedAt) {

        boolean isOlderThan(int seconds) {
            return Duration.between(savedAt, Instant.now()).getSeconds() > seconds;
        }
    }
}
