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

/**
 * Runs one complete scan, in this order:
 *
 *   1. Ask Google Safe Browsing if the address is already known to be bad.
 *   2. Run the rule checks (HTTPS, certificate, mixed content, address patterns).
 *   3. Ask the scoring engine for a 0-100 score.
 *   4. Join the score and the rules into the final score.
 *   5. Ask Claude to write the explanation, or use the template if that fails.
 *   6. Save the record and update the flagged summary.
 *
 * Results are cached per domain for a short time so browsing around one website
 * does not scan it again on every page.
 */
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

        // Pick the engine named in the settings. This is the one line that
        // switches between Version A and Version B.
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

    /**
     * Scan one URL.
     *
     * @param performLiveChecks false to skip opening the website. Used by the
     *        evaluation script so the run is fast and repeatable.
     */
    public ScanRecord scan(String rawUrl, String clientId, boolean performLiveChecks) {
        long started = System.nanoTime();
        String url = UrlUtils.normalise(rawUrl);
        String domain = UrlUtils.domainOf(url);

        // A cached result for the same domain is reused for a short time, so
        // moving between pages of one website does not scan it again.
        //
        // The cache is skipped in evaluation mode. Two different addresses on
        // the same website must each be scored on their own, otherwise a few
        // test URLs would silently receive another URL's result and the
        // measured accuracy would not be exactly reproducible.
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
        Explanation explanation = explainer.explain(url, checks, finalRisk);

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
            // Saving must never break the user's scan. The extension still gets
            // its answer; only the history entry is lost.
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
