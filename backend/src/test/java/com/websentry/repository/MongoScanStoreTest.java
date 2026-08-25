package com.websentry.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;

import com.mongodb.client.MongoClients;
import com.websentry.model.FlaggedUrl;
import com.websentry.model.RuleChecks;
import com.websentry.model.ScanRecord;

import de.flapdoodle.embed.mongo.distribution.Version;
import de.flapdoodle.embed.mongo.transitions.Mongod;
import de.flapdoodle.embed.mongo.transitions.RunningMongodProcess;
import de.flapdoodle.reverse.TransitionWalker;

/**
 * Tests the real MongoDB store against a real MongoDB.
 *
 * A temporary MongoDB is started for these tests, so the database code is
 * proved to work without MongoDB being installed on this machine. Until now
 * this class had never been run: the system falls back to the in-memory store
 * when no database address is set, so a fault here would only have appeared on
 * the day a real database was connected.
 */
class MongoScanStoreTest {

    private static TransitionWalker.ReachedState<RunningMongodProcess> running;
    private static MongoTemplate template;
    private static MongoScanStore store;

    @BeforeAll
    static void startDatabase() {
        running = Mongod.instance().start(Version.Main.V7_0);
        String address = running.current().getServerAddress().toString();

        template = new MongoTemplate(new SimpleMongoClientDatabaseFactory(
                MongoClients.create("mongodb://" + address), "websentry_test"));
        store = new MongoScanStore(template);
    }

    @AfterAll
    static void stopDatabase() {
        if (running != null) {
            running.close();
        }
    }

    @BeforeEach
    void clear() {
        template.dropCollection(ScanRecord.class);
        template.dropCollection(FlaggedUrl.class);
    }

    private ScanRecord record(String domain, int score, String band) {
        ScanRecord r = new ScanRecord();
        r.setUrl("https://" + domain);
        r.setDomain(domain);
        r.setTimestamp(Instant.now());
        r.setRiskScore(score);
        r.setRiskBand(band);
        r.setPhishing("HIGH".equals(band));
        r.setChecks(new RuleChecks(true, true, "Test CA", 90, false, false, false, List.of()));
        r.setRecommendation("Stay Safe");
        r.setClientId("test-client");
        r.setScanDurationMs(12);
        return r;
    }

    @Test
    @DisplayName("A scan is saved and can be read back by its id")
    void savesAndReadsBack() {
        ScanRecord saved = store.save(record("example.com", 10, "LOW"));

        assertTrue(saved.getId() != null, "MongoDB should have given the record an id");
        assertTrue(store.findById(saved.getId()).isPresent());
        assertEquals("example.com", store.findById(saved.getId()).get().getDomain());
    }

    @Test
    @DisplayName("Scans come back newest first")
    void newestFirst() {
        ScanRecord older = record("old.com", 5, "LOW");
        older.setTimestamp(Instant.now().minus(1, ChronoUnit.HOURS));
        store.save(older);
        store.save(record("new.com", 90, "HIGH"));

        PagedScans page = store.findAll(0, 25);

        assertEquals(2, page.totalItems());
        assertEquals("new.com", page.items().get(0).getDomain());
    }

    @Test
    @DisplayName("Paging returns the right slice and page count")
    void paging() {
        for (int i = 0; i < 7; i++) {
            store.save(record("site" + i + ".com", i, "LOW"));
        }

        PagedScans first = store.findAll(0, 3);

        assertEquals(7, first.totalItems());
        assertEquals(3, first.items().size());
        assertEquals(3, first.totalPages());
    }

    @Test
    @DisplayName("History is filtered to one client")
    void historyIsPerClient() {
        store.save(record("mine.com", 10, "LOW"));
        ScanRecord other = record("theirs.com", 10, "LOW");
        other.setClientId("someone-else");
        store.save(other);

        List<ScanRecord> mine = store.findRecentByClient("test-client", 20);

        assertEquals(1, mine.size());
        assertEquals("mine.com", mine.get(0).getDomain());
    }

    @Test
    @DisplayName("Only scans newer than the given time are returned")
    void findSince() {
        ScanRecord old = record("old.com", 5, "LOW");
        old.setTimestamp(Instant.now().minus(10, ChronoUnit.DAYS));
        store.save(old);
        store.save(record("recent.com", 5, "LOW"));

        List<ScanRecord> recent = store.findSince(Instant.now().minus(1, ChronoUnit.DAYS));

        assertEquals(1, recent.size());
        assertEquals("recent.com", recent.get(0).getDomain());
    }

    @Test
    @DisplayName("Flagging the same website twice adds to the counter instead of making a second row")
    void flaggedCounterAddsUp() {
        // This is the part most likely to be wrong, because it uses an upsert
        // rather than a plain save.
        ScanRecord first = record("bad.com", 80, "HIGH");
        store.recordFlagged(first);
        store.recordFlagged(record("bad.com", 95, "HIGH"));

        List<FlaggedUrl> flagged = store.topFlagged(10);

        assertEquals(1, flagged.size());
        assertEquals("bad.com", flagged.get(0).getDomain());
        assertEquals(2, flagged.get(0).getHitCount());
        assertEquals(95, flagged.get(0).getLastRiskScore(), "should hold the newest score");
        assertTrue(flagged.get(0).getFirstSeen() != null, "first seen must be kept");
    }

    @Test
    @DisplayName("The most flagged website is listed first")
    void topFlaggedIsSorted() {
        store.recordFlagged(record("once.com", 80, "HIGH"));
        for (int i = 0; i < 3; i++) {
            store.recordFlagged(record("often.com", 80, "HIGH"));
        }

        List<FlaggedUrl> flagged = store.topFlagged(10);

        assertEquals("often.com", flagged.get(0).getDomain());
        assertEquals(3, flagged.get(0).getHitCount());
    }

    @Test
    @DisplayName("Counting works")
    void counts() {
        store.save(record("a.com", 1, "LOW"));
        store.save(record("b.com", 2, "LOW"));

        assertEquals(2, store.count());
    }

    @Test
    @DisplayName("The nested check results survive a save and read")
    void nestedChecksSurvive() {
        // A scan record holds a RuleChecks object inside it. If Spring cannot
        // map that nested type, the record saves but comes back incomplete.
        ScanRecord saved = store.save(record("example.com", 10, "LOW"));

        ScanRecord read = store.findById(saved.getId()).orElseThrow();

        assertTrue(read.getChecks() != null, "the checks object must come back");
        assertEquals("Test CA", read.getChecks().sslIssuer());
        assertEquals(90, read.getChecks().sslDaysToExpiry());
    }
}
