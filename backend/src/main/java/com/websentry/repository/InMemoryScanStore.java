package com.websentry.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.websentry.model.FlaggedUrl;
import com.websentry.model.ScanRecord;

/**
 * Keeps scans in memory only. Everything is lost when the backend stops.
 *
 * This exists so the browser extension demo still works when MongoDB is not
 * reachable. It is not the real store and is never used when MONGODB_URI is set.
 */
public class InMemoryScanStore implements ScanStore {

    /** Keeps the newest scans only, so a long demo cannot fill up memory. */
    private static final int MAX_RECORDS = 5000;

    private final Map<String, ScanRecord> byId = new ConcurrentHashMap<>();
    private final Map<String, FlaggedUrl> flagged = new ConcurrentHashMap<>();

    @Override
    public synchronized ScanRecord save(ScanRecord record) {
        if (record.getId() == null) {
            record.setId(UUID.randomUUID().toString());
        }
        byId.put(record.getId(), record);
        if (byId.size() > MAX_RECORDS) {
            // Drop the oldest record to keep memory bounded.
            newestFirst().stream()
                    .skip(MAX_RECORDS)
                    .map(ScanRecord::getId)
                    .toList()
                    .forEach(byId::remove);
        }
        return record;
    }

    @Override
    public Optional<ScanRecord> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<ScanRecord> findRecentByClient(String clientId, int limit) {
        return newestFirst().stream()
                .filter(r -> clientId != null && clientId.equals(r.getClientId()))
                .limit(limit)
                .toList();
    }

    @Override
    public PagedScans findAll(int page, int size) {
        List<ScanRecord> all = newestFirst();
        int from = Math.min(page * size, all.size());
        int to = Math.min(from + size, all.size());
        int totalPages = size == 0 ? 0 : (int) Math.ceil(all.size() / (double) size);
        return new PagedScans(all.subList(from, to), page, size, all.size(), totalPages);
    }

    @Override
    public List<ScanRecord> findSince(Instant since) {
        return newestFirst().stream()
                .filter(r -> r.getTimestamp() != null && r.getTimestamp().isAfter(since))
                .toList();
    }

    @Override
    public long count() {
        return byId.size();
    }

    @Override
    public List<FlaggedUrl> topFlagged(int limit) {
        return flagged.values().stream()
                .sorted(Comparator.comparingLong(FlaggedUrl::getHitCount).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public void recordFlagged(ScanRecord record) {
        flagged.compute(record.getDomain(), (domain, existing) -> {
            FlaggedUrl row = existing != null ? existing : new FlaggedUrl(domain);
            if (row.getFirstSeen() == null) {
                row.setFirstSeen(record.getTimestamp());
            }
            row.setHitCount(row.getHitCount() + 1);
            row.setLastSeen(record.getTimestamp());
            row.setLastRiskScore(record.getRiskScore());
            row.setLastRiskBand(record.getRiskBand());
            return row;
        });
    }

    @Override
    public String describe() {
        return "in-memory (development fallback, data is not saved)";
    }

    /** All records sorted newest first. */
    private List<ScanRecord> newestFirst() {
        List<ScanRecord> all = new ArrayList<>(byId.values());
        all.sort(Comparator.comparing(
                ScanRecord::getTimestamp,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return all;
    }
}
