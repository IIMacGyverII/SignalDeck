/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.ham;

import static org.junit.Assert.*;

import org.junit.Test;

public class FoxSignalGateTest {
    static FoxSignalGate.Fox tone(double hz) { FoxSignalGate.Fox f = new FoxSignalGate.Fox(); f.only = true; f.toneHz = hz; return f; }
    static FoxSignalGate.Fox dmr(int id, int cc) { FoxSignalGate.Fox f = new FoxSignalGate.Fox(); f.only = true; f.digital = true; f.id = id; f.cc = cc; return f; }
    static FoxSignalGate.Now now(long t, boolean rx, double toneHz) { FoxSignalGate.Now n = new FoxSignalGate.Now(); n.t = t; n.rx = rx; n.toneHz = toneHz; return n; }
    static FoxSignalGate.Now call(long t, int caller, int cc, int channelCc) { FoxSignalGate.Now n = now(t, true, 0); n.caller = caller; n.cc = cc; n.channelCc = channelCc; return n; }

    @Test public void anySignalCountsEveryReceptionButNotOurOwnTransmission() {
        FoxSignalGate.Fox any = new FoxSignalGate.Fox();
        assertTrue(FoxSignalGate.matches(any, now(0, true, 0)));
        assertFalse(FoxSignalGate.matches(any, now(0, false, 0)));
        FoxSignalGate.Now tx = now(0, true, 0); tx.tx = true;
        assertFalse(FoxSignalGate.matches(any, tx));
    }

    @Test public void toneMatchAndMismatch() {
        FoxSignalGate.Fox f = tone(100.0);
        assertTrue(FoxSignalGate.matches(f, now(0, true, 100.0)));
        assertFalse("another station's tone", FoxSignalGate.matches(f, now(0, true, 103.5)));
        assertFalse("no tone decoded yet", FoxSignalGate.matches(f, now(0, true, 0)));
        assertFalse("tone left over after the reception", FoxSignalGate.matches(f, now(0, false, 100.0)));
    }

    @Test public void dcsNeedsTheModuleSquelchAndNotSoftSq() {
        FoxSignalGate.Fox f = new FoxSignalGate.Fox(); f.only = true; f.dcs = true;
        FoxSignalGate.Now n = now(0, true, 0); n.mcuRx = true;
        assertTrue(FoxSignalGate.matches(f, n));
        n.softSq = true; assertFalse(FoxSignalGate.matches(f, n));
        n.softSq = false; n.mcuRx = false; assertFalse(FoxSignalGate.matches(f, n));
    }

    @Test public void dmrCallerAndColourCode() {
        FoxSignalGate.Fox f = dmr(3100123, 3);
        assertTrue(FoxSignalGate.matches(f, call(0, 3100123, 3, 1)));
        assertTrue("no CC reported: the channel's", FoxSignalGate.matches(f, call(0, 3100123, -1, 3)));
        assertFalse("another caller", FoxSignalGate.matches(f, call(0, 3100999, 3, 3)));
        assertFalse("caller not known yet", FoxSignalGate.matches(f, call(0, 0, 3, 3)));
        assertFalse("another colour code", FoxSignalGate.matches(f, call(0, 3100123, 1, 3)));
        assertTrue("any CC", FoxSignalGate.matches(dmr(3100123, -1), call(0, 3100123, 7, 1)));
    }

    /** A transmission from t0 to t1 with the tone, readings every 250 ms, ticks every 250 ms; returns the gate. */
    static FoxSignalGate run(FoxSignalGate g, long t0, long t1, long tunedAt, int dbm) {
        for (long t = t0; t <= t1 + 1000; t += 250) {
            boolean in = t < t1;
            g.reading(t, in ? dbm : -120, 0, tunedAt);
            g.update(now(t, in, in ? 100.0 : 0));
        }
        return g;
    }

    @Test public void readingsSkipTheStartTheSettleAndTheTail() {
        FoxSignalGate g = new FoxSignalGate(); g.setFox(tone(100.0));
        java.util.List<FoxSignalGate.Reading> got = new java.util.ArrayList<>();
        for (long t = 0; t <= 5000; t += 250) {
            boolean in = t >= 1000 && t < 4000;
            g.reading(t, in ? (t >= 3500 ? -120 : -70) : -125, 0, 0);    // the last 0.5 s: carrier gone, squelch still open
            g.update(now(t, in, in ? 100.0 : 0));
            got.addAll(g.drain());
        }
        assertFalse(got.isEmpty());
        for (FoxSignalGate.Reading r : got) {
            assertTrue("past the start", r.at >= 1000 + FoxSignalGate.START_MS);
            assertTrue("not in the tail", r.at <= 4000 - FoxSignalGate.TAIL_MS + 250);
            assertEquals(-70, r.dbm);
        }
        // a retune 0.5 s into the transmission: nothing before it settles
        FoxSignalGate g2 = new FoxSignalGate(); g2.setFox(tone(100.0)); got.clear();
        for (long t = 0; t <= 5000; t += 250) { boolean in = t < 4000; g2.reading(t, -70, 0, 500); g2.update(now(t, in, in ? 100.0 : 0)); got.addAll(g2.drain()); }
        for (FoxSignalGate.Reading r : got) assertTrue(r.at >= 500 + FoxSignalGate.SETTLE_MS);
        assertFalse(got.isEmpty());
    }

    @Test public void staleReadingsAreNotCountedTwice() {
        FoxSignalGate g = new FoxSignalGate(); g.setFox(tone(100.0));
        int n = 0;
        for (long t = 0; t <= 4000; t += 250) { g.reading(500, -70, 0, 0); g.update(now(t, true, 100.0)); n += g.drain().size(); }
        assertTrue(n <= 1);
    }

    @Test public void markWaitsForTheFoxThenTakesIt() {
        FoxSignalGate g = new FoxSignalGate(); g.setFox(tone(100.0));
        g.update(now(0, true, 88.5));                      // another station: not the fox
        g.requestMark();
        assertEquals(-999, g.takeIfReady()); assertTrue(g.waiting());
        int took = -999;
        for (long t = 1000; t <= 4000 && took == -999; t += 250) { g.reading(t, -72, 0, 0); g.update(now(t, true, 100.0)); took = g.takeIfReady(); }
        assertEquals(-72, took); assertFalse(g.waiting());
        g.requestMark(); g.cancel(); assertFalse(g.waiting()); assertEquals(-999, g.takeIfReady());
    }

    @Test public void autoMarkOncePerTransmissionWhenMovedFarEnough() {
        FoxSignalGate g = new FoxSignalGate(); g.setFox(tone(100.0));
        for (long t = 0; t <= 2000; t += 250) { g.reading(t, -80, 0, 0); g.update(now(t, true, 100.0)); }
        assertEquals("off", -999, g.autoTake(false, Double.NaN, 50));
        assertEquals("first mark: no distance needed", -80, g.autoTake(true, Double.NaN, 50));
        assertEquals("once per transmission", -999, g.autoTake(true, 500, 50));
        g.update(now(2500, false, 0));                    // the fox stops
        for (long t = 3000; t <= 5000; t += 250) { g.reading(t, -75, 0, 0); g.update(now(t, true, 100.0)); }
        assertEquals("not moved far enough", -999, g.autoTake(true, 20, 50));
        assertEquals(-75, g.autoTake(true, 60, 50));
        // never while transmitting: our own key ends the match
        FoxSignalGate.Now tx = now(5250, true, 100.0); tx.tx = true; g.update(tx);
        assertFalse(g.active()); assertEquals(-999, g.autoTake(true, 1000, 50));
    }

    @Test public void steadyHeadingAcrossNorth() {
        assertEquals(0, FoxSignalGate.diff(FoxSignalGate.steadyHeading(new double[] { 355, 2, 358, 4 }, 10), 0), 3);
        assertTrue(Double.isNaN(FoxSignalGate.steadyHeading(new double[] { 10, 60, 30 }, 10)));
        assertTrue(Double.isNaN(FoxSignalGate.steadyHeading(new double[] { 10, Double.NaN }, 10)));
    }
}
