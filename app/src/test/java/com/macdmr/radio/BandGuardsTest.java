/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** A radio that covers one band never tunes, offers or creates a channel outside it; the dual-band Armor is unchanged. */
public class BandGuardsTest {
    @After public void builtInAgain() { ProfileRegistry.useForTest(null); }

    private static ChannelStore.Channel ch(int n, int rx, int tx) { ChannelStore.Channel c = new ChannelStore.Channel(); c.number = n; c.name = "C" + n; c.type = 1; c.rxFreq = rx; c.txFreq = tx; return c; }
    private static ChannelPresets.Preset preset(String zone) { for (ChannelPresets.Preset p : ChannelPresets.all()) if (p.zone.equals(zone)) return p; throw new AssertionError("no preset " + zone); }

    @Test public void aChannelIsTunableOnlyWithBothFrequenciesInBand() {
        DeviceProfile w6 = DeviceProfile.ANYSECU_W6, armor = DeviceProfile.ARMOR_26_ULTRA;
        ChannelStore.Channel uhf = ch(1, 446_006_250, 446_006_250), vhf = ch(2, 146_520_000, 146_520_000), split = ch(3, 437_800_000, 145_990_000), top = ch(4, 475_000_000, 475_000_000);
        assertTrue(ChannelStore.tunable(w6, uhf)); assertFalse(ChannelStore.tunable(w6, vhf));
        assertFalse("a 2 m uplink: the module refuses the channel", ChannelStore.tunable(w6, split));
        assertFalse("above the W6's 470 MHz", ChannelStore.tunable(w6, top));
        for (ChannelStore.Channel c : new ChannelStore.Channel[] { uhf, vhf, split, top }) assertTrue("the Armor tunes all of them", ChannelStore.tunable(armor, c));
        assertFalse(ChannelStore.tunable(armor, null));
        ProfileRegistry.useForTest(w6);
        List<ChannelStore.Channel> left = ChannelStore.tunable(Arrays.asList(vhf, uhf, split, top));
        assertEquals(1, left.size()); assertSame(uhf, left.get(0));
        assertTrue(ChannelStore.tunable(uhf)); assertFalse(ChannelStore.tunable(vhf));
        ProfileRegistry.useForTest(armor);
        assertEquals("the Armor's lists are unchanged", 4, ChannelStore.tunable(Arrays.asList(vhf, uhf, split, top)).size());
    }

    @Test public void presetsCountWhatTheRadioTunes() {
        DeviceProfile w6 = DeviceProfile.ANYSECU_W6, atom = DeviceProfile.ATOM_XL, armor = DeviceProfile.ARMOR_26_ULTRA;
        ChannelPresets.Preset noaa = preset("NOAA Weather"), ham = preset("Ham simplex"), frs = preset("FRS"), cb = preset("UHF CB");
        assertEquals("NOAA is VHF: hidden on the W6", 0, noaa.tunableCount(w6));
        assertEquals(frs.channels.size(), frs.tunableCount(w6));
        int hamUhf = 0; for (ChannelPresets.Ch c : ham.channels) if (c.rx >= 400_000_000) hamUhf++;
        assertTrue(hamUhf > 0 && hamUhf < ham.channels.size());
        assertEquals("n of m: the 70 cm ones", hamUhf, ham.tunableCount(w6));
        assertEquals("476-477 MHz is above the W6's band", 0, cb.tunableCount(w6));
        assertEquals("but inside the Atom XL's", cb.channels.size(), cb.tunableCount(atom));
        for (ChannelPresets.Preset p : ChannelPresets.all()) assertEquals(p.zone + " on the Armor", p.channels.size(), p.tunableCount(armor));
        String pv = ChannelPresets.preview(ham, w6), pa = ChannelPresets.preview(ham, armor);
        assertTrue(pv.contains("not tunable here")); assertFalse(pa.contains("not tunable here"));
        assertEquals(ham.channels.size() - hamUhf, pv.split("not tunable here", -1).length - 1);
    }

    @Test public void roamingNeverPicksARepeaterOutsideTheBand() {
        ProfileRegistry.useForTest(DeviceProfile.ANYSECU_W6);
        ChannelStore.Channel a = ch(1, 442_000_000, 447_000_000), vhf = ch(2, 145_000_000, 144_400_000), uhf = ch(3, 443_000_000, 448_000_000);
        for (ChannelStore.Channel c : new ChannelStore.Channel[] { a, vhf, uhf }) { c.type = 0; c.txContact = 3100; c.contactType = 1; }
        a.lat = 51.0; a.lon = -2.0; vhf.lat = 51.5; vhf.lon = -2.0; uhf.lat = 51.45; uhf.lon = -2.0;
        assertNull("the nearer 2 m repeater is skipped", Roaming.choose(a, Arrays.asList(a, vhf), 51.5, -2.0));
        assertSame(uhf, Roaming.choose(a, Arrays.asList(a, vhf, uhf), 51.5, -2.0));
        ProfileRegistry.useForTest(DeviceProfile.ARMOR_26_ULTRA);
        assertSame("the Armor roams to it as before", vhf, Roaming.choose(a, Arrays.asList(a, vhf), 51.5, -2.0));
    }

    @Test public void schedulesAndScopeFollowTheBands() {
        DeviceProfile w6 = DeviceProfile.ANYSECU_W6, armor = DeviceProfile.ARMOR_26_ULTRA;
        assertFalse(Scheduler.available(w6, Scheduler.APRS)); assertTrue(Scheduler.available(w6, Scheduler.LISTEN)); assertTrue(Scheduler.available(armor, Scheduler.APRS));
        Scheduler.Entry e = new Scheduler.Entry(); e.action = Scheduler.APRS;
        assertNotNull(Scheduler.bandProblem(w6, e, null)); assertNull(Scheduler.bandProblem(armor, e, null));
        e.action = Scheduler.WINLINK; ChannelStore.Channel vhf = ch(5, 145_050_000, 145_050_000);
        assertTrue(Scheduler.bandProblem(w6, e, vhf).contains("400–470 MHz")); assertNull(Scheduler.bandProblem(armor, e, vhf));
        e.action = Scheduler.SCAN; assertNull("an action without a channel of its own", Scheduler.bandProblem(w6, e, vhf));
        for (BandScope.Preset p : BandScope.PRESETS) assertTrue(p.name + " on the Armor", BandScope.fits(armor, p));
        boolean any = false; for (BandScope.Preset p : BandScope.PRESETS) { if (p.from < 300_000_000) assertFalse(p.name + " on the W6", BandScope.fits(w6, p)); any |= BandScope.fits(w6, p); }
        assertTrue("the W6 keeps its UHF presets", any);
        ProfileRegistry.useForTest(w6);
        int first = BandScope.firstFitting(); assertTrue(first < BandScope.PRESETS.length && BandScope.PRESETS[first].from >= 400_000_000);
        ProfileRegistry.useForTest(armor);
        assertEquals("the Armor's default preset is unchanged", 0, BandScope.firstFitting());
    }

    @Test public void theFirstRunListenIsATunableFrequency() {
        assertEquals(446_006_250L, DeviceProfile.ARMOR_26_ULTRA.listenTestHz());
        assertEquals(446_006_250L, DeviceProfile.ANYSECU_W6.listenTestHz());
        DeviceProfile vhfOnly = DeviceProfile.ANYSECU_W6.copy(true, false); vhfOnly.bandsHz = new long[] { 136_000_000L, 174_000_000L };
        long hz = vhfOnly.listenTestHz();
        assertTrue(vhfOnly.tunable(hz)); assertEquals(155_000_000L, hz); assertEquals(0, hz % 12_500);
    }

    /** The profiles added since the guards (refresh 2026-10-31): the 3WT's 100–174 / 400–470 MHz, the 20WT and Xplore 1 as listed, the W6Plus DMR UHF only. */
    @Test public void theNewerProfilesKeepTheirBands() {
        DeviceProfile wt3 = DeviceProfile.ARMOR_3WT, wt20 = DeviceProfile.ARMOR_20WT, xp = DeviceProfile.XPLORE_1, w6d = DeviceProfile.ANYSECU_W6_DMR;
        ChannelStore.Channel air = ch(1, 120_000_000, 120_000_000), aprs = ch(2, 144_390_000, 144_390_000), u475 = ch(3, 475_000_000, 475_000_000), frs = ch(4, 462_562_500, 462_562_500);
        assertTrue("the 3WT reaches below 136 MHz", ChannelStore.tunable(wt3, air)); assertTrue(ChannelStore.tunable(wt3, aprs));
        assertFalse("above the 3WT's 470 MHz", ChannelStore.tunable(wt3, u475)); assertTrue(ChannelStore.tunable(wt3, frs));
        for (DeviceProfile p : new DeviceProfile[] { wt20, xp }) { assertFalse(ChannelStore.tunable(p, air)); assertTrue(ChannelStore.tunable(p, aprs)); assertTrue(ChannelStore.tunable(p, u475)); }
        assertFalse(ChannelStore.tunable(w6d, aprs)); assertTrue(ChannelStore.tunable(w6d, u475));
        assertTrue(Scheduler.available(wt3, Scheduler.APRS)); assertFalse(Scheduler.available(w6d, Scheduler.APRS));
        assertFalse("a region whose APRS frequency is outside the bands: no APRS monitoring", Scheduler.available(wt3, Scheduler.APRS, 0));
    }
}
