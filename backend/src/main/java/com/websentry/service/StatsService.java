package com.websentry.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.websentry.config.WebSentryProperties;
import com.websentry.model.ScanRecord;
import com.websentry.repository.ScanStore;

/** Works out the numbers shown on the admin dashboard. */
@Service
public class StatsService {

    private static final Logger log = LoggerFactory.getLogger(StatsService.class);

    private final ScanStore store;
    private final RestClient mlClient;

    public StatsService(ScanStore store, RestClient.Builder builder, WebSentryProperties properties) {
        this.store = store;
        this.mlClient = builder.baseUrl(properties.getMl().getUrl()).build();
    }

    /** Totals, risk band breakdown and a scans-per-day series. */
    public Map<String, Object> summary(int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        List<ScanRecord> recent = store.findSince(since);

        long low = recent.stream().filter(r -> "LOW".equals(r.getRiskBand())).count();
        long medium = recent.stream().filter(r -> "MEDIUM".equals(r.getRiskBand())).count();
        long high = recent.stream().filter(r -> "HIGH".equals(r.getRiskBand())).count();

        double averageMs = recent.stream()
                .mapToLong(ScanRecord::getScanDurationMs)
                .average()
                .orElse(0.0);

        long aiWritten = recent.stream().filter(ScanRecord::isExplanationFromAi).count();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("totalScansAllTime", store.count());
        body.put("windowDays", days);
        body.put("scansInWindow", recent.size());
        body.put("bandCounts", Map.of("LOW", low, "MEDIUM", medium, "HIGH", high));
        body.put("averageScanMs", Math.round(averageMs));
        body.put("aiExplanationCount", aiWritten);
        body.put("templateExplanationCount", recent.size() - aiWritten);
        body.put("scansPerDay", scansPerDay(recent, days));
        return body;
    }

    /** One entry per day, oldest first, including days with no scans. */
    private List<Map<String, Object>> scansPerDay(List<ScanRecord> records, int days) {
        Map<LocalDate, Long> counts = new LinkedHashMap<>();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        for (int offset = days - 1; offset >= 0; offset--) {
            counts.put(today.minusDays(offset), 0L);
        }
        for (ScanRecord record : records) {
            if (record.getTimestamp() == null) {
                continue;
            }
            LocalDate day = record.getTimestamp().atZone(ZoneOffset.UTC).toLocalDate();
            counts.computeIfPresent(day, (key, value) -> value + 1);
        }

        List<Map<String, Object>> series = new ArrayList<>();
        counts.forEach((day, count) -> series.add(Map.of("date", day.toString(), "count", count)));
        return series;
    }

    /** Passes the model figures through from the ML service. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> modelInfo() {
        try {
            Map<String, Object> response = mlClient.get()
                    .uri("/model/info")
                    .retrieve()
                    .body(Map.class);
            return response == null ? Map.of("available", false) : response;
        } catch (Exception e) {
            log.warn("Could not read model info: {}", e.toString());
            return Map.of("available", false, "reason", "ML service is not running");
        }
    }
}
