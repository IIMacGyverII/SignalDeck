/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

/** tx-report.json: schema round trip, the automatic verdicts, redaction of callsign and DMR ID, who may run the session. */
public class TxReportTest {

    private static TxReport.Key key(long dur, long start, long stop, String by) {
        TxReport.Key k = new TxReport.Key(); k.startUtc = "2026-10-29T12:00:00.000Z"; k.plannedMs = 5000; k.durationMs = dur; k.sendStartMs = start; k.sendStopMs = stop; k.releasedBy = by;
        k.pttPath = "SignalDeck's own key -> module command 0x26 (launch) on /dev/ttyS1";
        if (start >= 0) { TxReport.Status s = new TxReport.Status(); s.tMs = start; s.code = 3; s.name = "SEND_START"; k.statuses.add(s); }
        if (stop >= 0) { TxReport.Status s = new TxReport.Status(); s.tMs = stop; s.code = 4; s.name = "SEND_STOP"; k.statuses.add(s); }
        TxReport.Channel c = new TxReport.Channel(); c.number = 3; c.name = "Simplex N0CALL"; c.mode = "FM"; c.rxHz = c.txHz = 446_006_250; c.power = "low"; c.bandwidth = "wide"; k.channel = c;
        k.batteryBefore = 30.5; k.batteryAfter = 31.0; k.processorBefore = null; k.processorAfter = 41.2;
        return k;
    }

    private static TxReport sample() {
        TxReport r = new TxReport();
        r.created = "2026-10-29T12:00:00.000Z"; r.finished = "2026-10-29T12:10:00.000Z"; r.module = "DMR003.UV4T.V022"; r.safetyUtc = "2026-10-29T11:59:00.000Z";
        for (String s : TxReport.SAFETY) r.safety.add(s);
        r.equipment = "RTL-SDR v4"; r.receiver = "UV-5R";
        r.build.commit = "abc1234567"; r.build.kind = BuildIdentity.FORK; r.build.profileId = "maker-phone"; r.build.profileVerified = true;
        r.steps.addAll(TxReport.plan(true));
        TxReport.Step fm = r.step("fm"); fm.keys.add(key(5000, 120, 5180, "app")); fm.heard = "yes"; fm.notes = "clear"; fm.result = TxReport.heardVerdict(fm.keys.get(0), fm.heard);
        TxReport.Step m = r.step("measure"); m.offsetHz = 620.0; m.deviationHz = null; m.result = TxReport.RECORDED;
        TxReport.Step d = r.step("dmr"); d.heard = "yes"; d.callerId = "no";
        TxReport.Recording rec = new TxReport.Recording(); rec.name = "capture.wav"; rec.sha256 = "ab".repeat(32); rec.bytes = 1234; r.recordings.add(rec);
        return r;
    }

    @Test public void schemaRoundTrip() throws Exception {
        TxReport r = sample();
        JSONObject o = r.toJson();
        assertEquals(1, o.getInt("schema"));
        assertEquals("signaldeck-tx-report", o.getString("type"));
        TxReport back = TxReport.fromJson(new JSONObject(o.toString()));
        assertEquals(o.toString(), back.toJson().toString());
        assertEquals(TxReport.PASS, back.step("fm").result);
        assertEquals(620.0, back.step("measure").offsetHz, 0);
        assertNull(back.step("measure").deviationHz);
        assertEquals(2, back.step("fm").keys.get(0).statuses.size());
        assertNull(back.step("fm").keys.get(0).processorBefore);
        assertEquals(6, back.steps.size());
        assertEquals(5, TxReport.plan(false).size());                                    // no DMR step on an FM-only phone
        assertTrue(back.summaryText().contains("FM voice key (5 s)"));
    }

    @Test(expected = org.json.JSONException.class) public void otherFilesAreRefused() throws Exception {
        TxReport.fromJson(new JSONObject().put("schema", 2).put("type", TxReport.TYPE));
    }

    @Test public void verdicts() {
        assertEquals(TxReport.PASS, TxReport.heardVerdict(key(5000, 100, 5100, "app"), "yes"));
        assertEquals(TxReport.FAIL, TxReport.heardVerdict(key(5000, 100, 5100, "app"), "no"));
        assertEquals(TxReport.RECORDED, TxReport.heardVerdict(key(5000, 100, 5100, "app"), "not checked"));
        assertEquals(TxReport.FAIL, TxReport.heardVerdict(key(5000, 100, -1, "app"), "yes"));          // no SEND_STOP: the key did not end
        TxReport.Key refused = key(0, -1, -1, "not keyed"); refused.refused = "receive-only";
        assertEquals(TxReport.FAIL, TxReport.heardVerdict(refused, "yes"));
        assertEquals(TxReport.PASS, TxReport.releaseVerdict(java.util.Arrays.asList(key(2000, 90, 2080, "app"), key(2000, 95, 2100, "app"))));
        assertEquals(TxReport.FAIL, TxReport.releaseVerdict(java.util.Arrays.asList(key(2000, 90, 2080, "app"), key(2000, 95, -1, "app"))));
        assertEquals(TxReport.PASS, TxReport.watchdogVerdict(key(28_050, 100, 28_200, "watchdog")));
        assertEquals(TxReport.FAIL, TxReport.watchdogVerdict(key(35_000, 100, 35_100, "session (35 s, the watchdog did not release)")));
        assertEquals(TxReport.SKIPPED, TxReport.watchdogVerdict(key(9_000, 100, 9_100, "tester")));
        assertEquals(TxReport.FAIL, TxReport.watchdogVerdict(key(12_000, 100, 12_100, "module")));
    }

    @Test public void callsignAndDmrIdAreRedacted() throws Exception {
        TxReport r = sample();
        r.step("fm").notes = "Heard N0CALL-7 clearly, n0call too; caller id 3101234 shown; mail me a@b.org";
        String json = TxReport.redact(r.toJson().toString(2), "N0CALL-9", 3101234);
        assertFalse(json.toUpperCase().contains("N0CALL"));
        assertFalse(json.contains("3101234"));
        assertTrue(json.contains("<callsign>"));
        assertTrue(json.contains("<dmr id>"));
        assertTrue(json.contains("***@***"));
        assertTrue(json.contains("ab".repeat(32)));                                        // the recording's hash is untouched
        assertTrue(json.contains("446006250"));                                            // and the frequency
        TxReport back = TxReport.fromJson(new JSONObject(json));                           // still a valid report
        assertEquals("Simplex <callsign>", back.step("fm").keys.get(0).channel.name);
        String sum = TxReport.redact(r.summaryText(), "N0CALL", 3101234);
        assertFalse(sum.toUpperCase().contains("N0CALL"));
        assertFalse(sum.contains("3101234"));
    }

    @Test public void redactionLeavesShortOrMissingIdentitiesAlone() {
        assertEquals("channel 1 at 446.00625", TxReport.redact("channel 1 at 446.00625", "", 1));       // the stock default ID 1 is not personal
        assertEquals("AB1 is not a callsign here", TxReport.redact("AB1 is not a callsign here", "AB", 0));
        assertEquals("x31012345y 31012345", TxReport.redact("x31012345y 31012345", null, 3101234));       // whole numbers only
        assertNull(TxReport.redact(null, "N0CALL", 3101234));
    }

    @Test public void whoMayRunTheSession() {
        TxReport.Gate g = TxReport.gate(false, false, true, BuildIdentity.OFFICIAL);       // unverified profile in an official build
        assertFalse(g.ok); assertFalse(g.fork); assertTrue(g.reason.contains("maintainer"));
        g = TxReport.gate(true, false, true, BuildIdentity.FORK);                          // a fork's own build with verified = true
        assertTrue(g.ok); assertTrue(g.fork); assertNull(g.reason);
        g = TxReport.gate(false, false, true, BuildIdentity.FORK);                         // a fork that kept verified = false
        assertFalse(g.ok); assertTrue(g.fork); assertTrue(g.reason.contains("verified = true"));
        g = TxReport.gate(true, false, false, BuildIdentity.FORK);                         // Restricted mode keeps transmit off
        assertFalse(g.ok); assertTrue(g.reason.contains("Restricted"));
        g = TxReport.gate(true, false, false, BuildIdentity.OFFICIAL);
        assertFalse(g.ok);
        g = TxReport.gate(true, true, true, BuildIdentity.OFFICIAL);                       // an advisory switched transmit off
        assertFalse(g.ok); assertTrue(g.reason.contains("advisory"));
        g = TxReport.gate(true, false, true, BuildIdentity.OFFICIAL);
        assertTrue(g.ok); assertFalse(g.fork);
        assertTrue(TxReport.gate(true, false, true, BuildIdentity.UNKNOWN).fork);          // not provably official: flagged
    }

    @Test public void anUntestedProfileMayAllowTheSessionOnly() {
        // the Armor 20WT (owner, 2026-10-30): the session may key it before it is verified; advisories and Restricted mode still win
        TxReport.Gate g = TxReport.gate(false, true, false, true, BuildIdentity.OFFICIAL);
        assertTrue(g.ok); assertNull(g.reason);
        assertFalse(TxReport.gate(false, true, true, true, BuildIdentity.OFFICIAL).ok);
        assertFalse(TxReport.gate(false, true, false, false, BuildIdentity.OFFICIAL).ok);
        assertFalse(TxReport.gate(false, false, false, true, BuildIdentity.OFFICIAL).ok);
        assertTrue(DeviceProfile.ARMOR_20WT.txTestUnverified);
        assertFalse(DeviceProfile.ARMOR_20WT.verified);
        for (DeviceProfile p : new DeviceProfile[] { DeviceProfile.ARMOR_26_ULTRA, DeviceProfile.ANYSECU_W6, DeviceProfile.ANYSECU_W6_DMR, DeviceProfile.ATOM_XL, DeviceProfile.UNKNOWN })
            assertFalse(p.name, p.txTestUnverified);
        assertTrue(DeviceProfile.ARMOR_20WT.copy(true, false).txTestUnverified);
        assertTrue(TxTestSession.pttPath(DeviceProfile.ARMOR_20WT).contains("600 ms before the key"));
    }

    @Test public void measuredValuesParse() {
        assertEquals(600.0, TxReport.parseHz("+600"), 0);
        assertEquals(-350.0, TxReport.parseHz("-350 Hz"), 0);
        assertEquals(2500.5, TxReport.parseHz("2500,5"), 0);
        assertNull(TxReport.parseHz(""));
        assertNull(TxReport.parseHz("about 600"));
        assertNull(TxReport.sha256OrNull("abc"));
        assertEquals("ab".repeat(32), TxReport.sha256OrNull("AB".repeat(32)));
    }

    @Test public void hashOfAStream() throws Exception {
        String[] h = TxReport.sha256(new java.io.ByteArrayInputStream("abc".getBytes("US-ASCII")));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", h[0]);
        assertEquals("3", h[1]);
    }
}
