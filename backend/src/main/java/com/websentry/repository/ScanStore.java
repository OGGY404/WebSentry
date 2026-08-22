package com.websentry.repository;

import java.util.List;
import java.util.Optional;

import com.websentry.model.FlaggedUrl;
import com.websentry.model.ScanRecord;

/**
 * Where scans are saved.
 *
 * There are two versions of this. MongoScanStore is the real one.
 * InMemoryScanStore is used when no MongoDB address is configured, so the
 * demo still runs if Atlas is down or the internet drops.
 */
public interface ScanStore {

    ScanRecord save(ScanRecord record);

    Optional<ScanRecord> findById(String id);

    /** Most recent scans for one extension user. */
    List<ScanRecord> findRecentByClient(String clientId, int limit);

    /** One page of all scans, newest first. Used by the dashboard. */
    PagedScans findAll(int page, int size);

    /** All scans newer than the given number of days. Used to build statistics. */
    List<ScanRecord> findSince(java.time.Instant since);

    long count();

    /** Top domains by how many times they were flagged as risky. */
    List<FlaggedUrl> topFlagged(int limit);

    /** Add one hit to the flagged summary for this scan. */
    void recordFlagged(ScanRecord record);

    /** Short name of the store, shown on the health endpoint. */
    String describe();
}
