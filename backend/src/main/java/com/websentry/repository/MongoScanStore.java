package com.websentry.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import com.websentry.model.FlaggedUrl;
import com.websentry.model.ScanRecord;

/** The real store. */
public class MongoScanStore implements ScanStore {

    private final MongoTemplate mongo;

    public MongoScanStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public ScanRecord save(ScanRecord record) {
        return mongo.save(record);
    }

    @Override
    public Optional<ScanRecord> findById(String id) {
        return Optional.ofNullable(mongo.findById(id, ScanRecord.class));
    }

    @Override
    public List<ScanRecord> findRecentByClient(String clientId, int limit) {
        Query query = new Query(Criteria.where("clientId").is(clientId))
                .with(Sort.by(Sort.Direction.DESC, "timestamp"))
                .limit(limit);
        return mongo.find(query, ScanRecord.class);
    }

    @Override
    public PagedScans findAll(int page, int size) {
        long total = mongo.count(new Query(), ScanRecord.class);
        Query query = new Query()
                .with(Sort.by(Sort.Direction.DESC, "timestamp"))
                .skip((long) page * size)
                .limit(size);
        List<ScanRecord> items = mongo.find(query, ScanRecord.class);
        int totalPages = size == 0 ? 0 : (int) Math.ceil(total / (double) size);
        return new PagedScans(items, page, size, total, totalPages);
    }

    @Override
    public List<ScanRecord> findSince(Instant since) {
        Query query = new Query(Criteria.where("timestamp").gt(since))
                .with(Sort.by(Sort.Direction.DESC, "timestamp"));
        return mongo.find(query, ScanRecord.class);
    }

    @Override
    public long count() {
        return mongo.count(new Query(), ScanRecord.class);
    }

    @Override
    public List<FlaggedUrl> topFlagged(int limit) {
        Query query = new Query()
                .with(Sort.by(Sort.Direction.DESC, "hitCount"))
                .limit(limit);
        return mongo.find(query, FlaggedUrl.class);
    }

    @Override
    public void recordFlagged(ScanRecord record) {
        // Upsert: add one to the counter, or make the row if this domain is new.
        Query query = new Query(Criteria.where("_id").is(record.getDomain()));
        Update update = new Update()
                .inc("hitCount", 1)
                .set("lastSeen", record.getTimestamp())
                .set("lastRiskScore", record.getRiskScore())
                .set("lastRiskBand", record.getRiskBand())
                .setOnInsert("firstSeen", record.getTimestamp());
        mongo.upsert(query, update, FlaggedUrl.class);
    }

    @Override
    public String describe() {
        return "mongodb";
    }
}
