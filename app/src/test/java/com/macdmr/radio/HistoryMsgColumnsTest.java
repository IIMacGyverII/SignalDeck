package com.macdmr.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * The history table's message columns (translations): an existing database gains msgKey and msgArgs once, and rows
 * without them keep working (MsgTest covers how they are shown). The SQLite side itself runs on the phone only.
 */
public class HistoryMsgColumnsTest {
    @Test public void oldTableGainsBothColumnsOnce() {
        Set<String> cols = new HashSet<>(Arrays.asList("_id", "channel", "ts", "kind", "text", "rssi", "dmrId"));
        assertEquals(Arrays.asList("ALTER TABLE history ADD COLUMN msgKey TEXT", "ALTER TABLE history ADD COLUMN msgArgs TEXT"), ChannelStore.historyMigrations(cols));
        cols.add("msgKey"); cols.add("msgArgs");
        assertTrue("an already migrated table needs nothing", ChannelStore.historyMigrations(cols).isEmpty());
    }

    @Test public void halfMigratedTableGetsTheRest() {
        Set<String> cols = new HashSet<>(Arrays.asList("_id", "channel", "ts", "kind", "text", "rssi", "dmrId", "msgKey"));
        assertEquals(Arrays.asList("ALTER TABLE history ADD COLUMN msgArgs TEXT"), ChannelStore.historyMigrations(cols));
    }
}
