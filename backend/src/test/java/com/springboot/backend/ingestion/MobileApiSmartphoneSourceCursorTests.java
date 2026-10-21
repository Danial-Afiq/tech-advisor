package com.springboot.backend.ingestion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real Postgres - loadCursor()/saveCursor() are plain SQL (ingestion_cursors, V12) with no
 * JPA entity to fall back on, same reasoning SmartphoneCatalogSinkTests already follows for
 * phone_variants. advance()'s own rule is pure and covered directly in
 * MobileApiSmartphoneSourceTests; this covers only the SQL round-trip advance() feeds into.
 */
@SpringBootTest
class MobileApiSmartphoneSourceCursorTests {
    @Autowired MobileApiSmartphoneSource source;
    @Autowired JdbcTemplate db;

    @BeforeEach void reset() {
        assertTrue(db.queryForObject("SELECT current_database()", String.class).endsWith("_test"),
                "Integration tests require a dedicated database whose name ends in _test");
        db.update("DELETE FROM ingestion_cursors WHERE source_id = 'mobileapi-smartphone'");
    }

    @Test void loadCursorStartsAtPageOneOfTheCurrentYearWhenNoRowExistsYet() {
        var cursor = source.loadCursor(2026);
        assertEquals(new MobileApiSmartphoneSource.Cursor(2026, 1), cursor);
    }

    @Test void saveThenLoadRoundTripsTheExactCursor() {
        source.saveCursor(new MobileApiSmartphoneSource.Cursor(2026, 7));
        assertEquals(new MobileApiSmartphoneSource.Cursor(2026, 7), source.loadCursor(2026));
    }

    @Test void savingTwiceUpsertsRatherThanFailingOnTheSecondInsert() {
        source.saveCursor(new MobileApiSmartphoneSource.Cursor(2026, 1));
        source.saveCursor(new MobileApiSmartphoneSource.Cursor(2026, 2));
        assertEquals(new MobileApiSmartphoneSource.Cursor(2026, 2), source.loadCursor(2026));
        assertEquals(1, db.queryForObject(
                "SELECT count(*) FROM ingestion_cursors WHERE source_id = 'mobileapi-smartphone'", Integer.class));
    }

    @Test void loadCursorResetsToPageOneWhenTheStoredYearNoLongerMatchesNow() {
        // Same rule advance() applies mid-run - applied here too so a cursor left idle over
        // a calendar rollover (e.g. the app was down 31 Dec -> 1 Jan) still resets correctly
        // on the very next run rather than resuming a year that's no longer "now".
        source.saveCursor(new MobileApiSmartphoneSource.Cursor(2025, 9));
        assertEquals(new MobileApiSmartphoneSource.Cursor(2026, 1), source.loadCursor(2026));
    }
}
