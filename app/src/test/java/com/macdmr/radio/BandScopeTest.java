/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.List;

public class BandScopeTest {
    @Test public void stepsRangesAndUnits() {
        assertEquals(161, BandScope.steps(144_000_000, 148_000_000, 25_000));
        assertEquals(0, BandScope.steps(148_000_000, 144_000_000, 25_000));
        assertTrue(BandScope.tunable(146_520_000)); assertFalse(BandScope.tunable(220_000_000)); assertTrue(BandScope.tunable(446_000_000));
        assertEquals(-79.0f, BandScope.dbm(82), 0.01f); assertTrue(Float.isNaN(BandScope.dbm(0))); assertTrue("raw 226 (\"-7 dBm\") is no reading", Float.isNaN(BandScope.dbm(226)));
        for (BandScope.Preset p : BandScope.PRESETS) {                         // every preset is a sweep the radio can do
            assertTrue(p.name, BandScope.tunable(p.from) && BandScope.tunable(p.to));
            int n = BandScope.steps(p.from, p.to, p.step); assertTrue(p.name + " " + n, n >= 2 && n <= BandScope.MAX_STEPS);
        }
    }
    @Test public void peaksStandOutOfTheNoise() {
        float[] s = { -105, -104.5f, -105, -79, -96, -105, Float.NaN, -104, -84, -105 };   // the bench sweep: a carrier, its skirt, one more signal
        List<int[]> p = BandScope.peaks(s, 147_500_000, 25_000, 8);
        assertEquals(2, p.size());
        assertEquals(147_575_000, p.get(0)[0]); assertEquals(-79, p.get(0)[1]);     // the skirt (-96) is not a separate peak
        assertEquals(147_700_000, p.get(1)[0]);
        assertTrue(BandScope.peaks(new float[] { -100, -100.5f, -96.5f, -101 }, 162_400_000, 25_000, 8).isEmpty());
    }

    @Test public void customRangesAreCheckedBeforeSweeping() {
        assertNull(BandScope.problem(462_500_000, 467_750_000, 12_500));
        assertNull(BandScope.problem(146_000_000, 146_100_000, 5_000));
        assertNotNull("the end below the start", BandScope.problem(146_100_000, 146_000_000, 5_000));
        assertNotNull("across the gap the radio cannot tune", BandScope.problem(170_000_000, 410_000_000, 100_000));
        assertNotNull("220 MHz", BandScope.problem(222_000_000, 225_000_000, 20_000));
        assertNotNull("too fine a step", BandScope.problem(146_000_000, 146_100_000, 1_000));
        assertNotNull("too many steps", BandScope.problem(420_000_000, 450_000_000, 12_500));
        assertNotNull("nothing typed", BandScope.problem(0, 0, 0));
    }

    @Test public void sweepTimeIsEstimatedFromTheRangeNotTheLastSweep() {
        BandScope s = new BandScope(null);
        assertEquals("GMRS: 421 steps of about a second", "7 min", BandScope.duration(421L * (BandScope.DEFAULT_SETTLE_MS + 300)));
        assertEquals("5 s", BandScope.duration(5_000));
        assertEquals("2.0 h", BandScope.duration(7_200_000));
        assertEquals(0, s.currentHz());
    }
    @Test public void aUhfOnlyRadioSweepsInsideItsBandOnly() {
        com.macdmr.radio.DeviceProfile atom = com.macdmr.radio.DeviceProfile.ATOM_XL;   // 400-480 MHz (DMR006.U4A)
        assertTrue("the bench sweep of 2026-10-29", BandScope.oneBand(atom, 400_000_000L, 401_200_000L));
        assertTrue(BandScope.oneBand(atom, 470_000_000L, 480_000_000L));
        assertFalse("the 2 m preset: refused on the Atom XL", BandScope.oneBand(atom, 146_400_000L, 146_640_000L));
        assertFalse("past the top of the band", BandScope.oneBand(atom, 479_000_000L, 481_000_000L));
        assertTrue("the Armor tunes 2 m", BandScope.oneBand(com.macdmr.radio.DeviceProfile.ARMOR_26_ULTRA, 146_400_000L, 146_640_000L));
    }

    @Test public void sweepReadingsFollowThePhonesScale() {
        assertEquals("the Armor, as before", -88f, BandScope.dbm(64), 0);
        assertTrue("the Armor's no-reading byte", Float.isNaN(BandScope.dbm(200)));
        assertEquals(BandScope.dbm(64), BandScope.dbm(64, 0.5, 0), 0);
        assertEquals("the Atom XL: one dB per step", -52f, BandScope.dbm(0x55, 1.0, -17), 0);
        assertTrue("above -20 dBm is no reading there too", Float.isNaN(BandScope.dbm(117, 1.0, -17)));
        assertTrue(Float.isNaN(BandScope.dbm(0, 1.0, -17)));
    }
}
