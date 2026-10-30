/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import static org.junit.Assert.*;

import org.junit.Test;

public class FirstRunCheckTest {

    private static RadioEngine.CommandStats stats(int sent, int answered, int refused) {
        RadioEngine.CommandStats s = new RadioEngine.CommandStats(); s.sent = sent; s.answered = answered; s.refused = refused; return s;
    }

    @Test public void listsOnlyTheCommandsTheStockAppDoesNotSend() {
        java.util.Map<Integer, RadioEngine.CommandStats> m = new java.util.HashMap<>();
        m.put(0x23, stats(3, 3, 0));                       // the stock app sends it: not listed
        m.put(0x32, stats(5, 5, 0));
        m.put(0x2B, stats(2, 0, 0));
        m.put(0x2F, stats(2, 2, 2));
        String r = FirstRunCheck.unusedCommands(DeviceProfile.ARMOR_20WT.stockCommands, m);
        assertFalse(r.contains("0x23"));
        assertTrue(r.contains("0x2B  2 / 0 / 0   <- no answer"));
        assertTrue(r.contains("0x2F  2 / 2 / 2   <- always refused"));
        assertTrue(r.contains("0x32  5 / 5 / 0\n"));
        assertTrue("in command order", r.indexOf("0x2B") < r.indexOf("0x2F") && r.indexOf("0x2F") < r.indexOf("0x32"));
        assertEquals("a profile without a stock command list adds nothing", "", FirstRunCheck.unusedCommands(null, m));
    }

    @Test public void seesThatTheProbeCouldNotSetTheSpeed() {
        // the Armor 20WT's beta.18 report: ttyS1 stayed at 9600 whatever stty was asked for
        String probe = "--- /dev/ttyS0 ---\nbefore: speed 921600 baud\n   57600 baud (readback 921600), after 0x34 query: (nothing received)\n\n"
                + "--- /dev/ttyS1 ---\nbefore: speed 9600 baud\n   57600 baud (readback 9600), after 0x34 query: (nothing received)\n  115200 baud (readback 115200), after 0x34 query: (nothing received)\n\n"
                + "--- /dev/ttyS2 ---\n   57600 baud (readback 57600), after 0x34 query: (nothing received)\n";
        assertTrue(FirstRunCheck.speedNotSet(probe, "/dev/ttyS1", 57600));
        assertFalse("set as asked", FirstRunCheck.speedNotSet(probe, "/dev/ttyS2", 57600));
        assertFalse("not probed", FirstRunCheck.speedNotSet(probe, "/dev/ttyS3", 57600));
        assertFalse(FirstRunCheck.speedNotSet(null, "/dev/ttyS1", 57600));
    }

    @Test public void readsTheSquelchCheck() {
        assertTrue(FirstRunCheck.squelchVerdict(true, 8000, true, 8000).contains("audio path works"));
        assertTrue(FirstRunCheck.squelchVerdict(false, 0, true, 8000).contains("only with monitor"));
        assertTrue(FirstRunCheck.squelchVerdict(true, 0, true, 0).contains("audio path is the problem"));
        assertTrue(FirstRunCheck.squelchVerdict(false, 0, false, 0).contains("still untested"));
    }

    @Test public void findsWhenTheReadingSettles() {
        java.util.List<long[]> pts = new java.util.ArrayList<>();
        pts.add(new long[] { 550, -121 }); pts.add(new long[] { 700, -105 }); pts.add(new long[] { 1000, -104 }); pts.add(new long[] { 1300, -103 });
        assertEquals(700, FirstRunCheck.settledAtMs(pts, 2));          // 15-20 dB low until ~0.7 s, as on the Armor 26 Ultra
        assertEquals(-1, FirstRunCheck.settledAtMs(new java.util.ArrayList<>(), 2));
        assertEquals("700", FirstRunCheck.median(java.util.Arrays.asList(900L, 700L, 650L)));
        assertEquals("?", FirstRunCheck.median(new java.util.ArrayList<>()));
    }

    @Test public void bandEdgesStraddleTheModuleLimits() {
        for (int i = 0; i < FirstRunCheck.EDGES_HZ.length; i += 2)       // each edge, then 5 kHz outside it
            assertEquals(5_000, Math.abs(FirstRunCheck.EDGES_HZ[i] - FirstRunCheck.EDGES_HZ[i + 1]));
    }
}
