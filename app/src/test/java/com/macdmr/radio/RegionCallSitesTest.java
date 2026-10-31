/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import static org.junit.Assert.*;

import com.macdmr.radio.region.Region;
import com.macdmr.radio.region.RegionTable;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** The places that used the US constants (144.390, the US band edges, ten minutes) follow the region (issue #95). */
public class RegionCallSitesTest {
    static RegionTable t;
    @BeforeClass public static void load() throws Exception { t = RegionTable.parse(new String(Files.readAllBytes(Paths.get("src/main/assets/regions/regions.json")), StandardCharsets.UTF_8)); }
    @After public void reset() { Region.setForTest(t, null); }

    @Test public void beaconsAndTheIgateFollowTheRegionsAprsFrequency() {
        int be = t.resolve("BE").aprsHz;
        assertTrue(PacketEngine.onAprsFrequency(true, 144_800_000, be, false));
        assertFalse("144.390 is not the APRS frequency in Belgium", PacketEngine.onAprsFrequency(true, 144_390_000, be, false));
        assertFalse("a TNC client owns the radio", PacketEngine.onAprsFrequency(true, 144_800_000, be, true));
        assertFalse("digital channel", PacketEngine.onAprsFrequency(false, 144_800_000, be, false));
        assertTrue("US unchanged", PacketEngine.onAprsFrequency(true, 144_390_000, t.resolve("US").aprsHz, false));
    }

    @Test public void cwIdAndTheIdTimerUseTheRegionsBands() {
        Region.setForTest(t, t.resolve("BE"));
        assertFalse(RadioService.amateurBand(146_520_000)); assertTrue(RadioService.amateurBand(145_500_000));
        Region.setForTest(t, t.resolve("NL"));
        assertEquals(5 * 60_000L, RadioService.idEveryMs()); assertEquals(5 * 60_000L, com.macdmr.radio.ham.Parrot.idEveryMs());
        Region.setForTest(t, t.resolve("US"));
        assertTrue(RadioService.amateurBand(146_520_000)); assertEquals(10 * 60_000L, RadioService.idEveryMs());
        Region.setForTest(t, null);
        assertTrue("before any region: the old US edges", RadioService.amateurBand(223_000_000));
    }

    @Test public void hamPresetAndSchedulerName() {
        ChannelPresets.Preset ham = null;
        for (ChannelPresets.Preset p : ChannelPresets.all(t.resolve("BE"))) if (p.zone.equals("Ham simplex")) ham = p;
        assertNotNull(ham);
        boolean aprs = false; for (ChannelPresets.Ch c : ham.channels) if (c.name.equals("APRS")) { aprs = true; assertEquals(144_800_000, c.rx); }
        assertTrue(aprs);
        assertEquals("APRS monitoring (144.800)", Scheduler.Entry.actionNames(144_800_000)[2]);
        assertEquals("APRS monitoring", Scheduler.ACTION_NAMES[2]);
    }
}
