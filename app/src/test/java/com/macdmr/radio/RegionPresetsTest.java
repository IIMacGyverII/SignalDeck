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
import com.macdmr.radio.repeaters.GeoUtil;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Step 3 of regions: the channel lists, the weather band, the band scope presets and the units follow the region. */
public class RegionPresetsTest {
    static RegionTable t;
    @BeforeClass public static void load() throws Exception { t = RegionTable.parse(new String(Files.readAllBytes(Paths.get("src/main/assets/regions/regions.json")), StandardCharsets.UTF_8)); }
    @After public void reset() { Region.setForTest(t, null); }

    private static ChannelPresets.Offer offer(List<ChannelPresets.Offer> l, String zone) { for (ChannelPresets.Offer o : l) if (o.preset.zone.equals(zone)) return o; return null; }
    private static int pos(List<ChannelPresets.Offer> l, String zone) { for (int i = 0; i < l.size(); i++) if (l.get(i).preset.zone.equals(zone)) return i; return -1; }
    private static List<String> names(ChannelPresets.Preset p) { List<String> n = new ArrayList<>(); for (ChannelPresets.Ch c : p.channels) n.add(c.name + "@" + c.rx); return n; }

    @Test public void listsCountWhatAUhfOnlyRadioCanUse() {
        java.util.function.LongPredicate uhf = hz -> hz >= 400_000_000L && hz <= 480_000_000L;   // the W6 phones and the Atom XL
        java.util.Map<String, ChannelPresets.Preset> by = new java.util.HashMap<>();
        for (ChannelPresets.Preset p : ChannelPresets.all(t.resolve("US"))) by.put(p.zone, p);
        assertEquals("Marine VHF: none", 0, ChannelPresets.tunableCount(by.get("Marine VHF"), uhf));
        assertEquals("FRS: all", by.get("FRS").channels.size(), ChannelPresets.tunableCount(by.get("FRS"), uhf));
        int ham = ChannelPresets.tunableCount(by.get("Ham simplex"), uhf);
        assertTrue("ham simplex: only its 70 cm channels", ham > 0 && ham < by.get("Ham simplex").channels.size());
        assertEquals("a radio that tunes everything uses every channel", by.get("Marine VHF").channels.size(), ChannelPresets.tunableCount(by.get("Marine VHF"), hz -> true));
    }

    @Test public void nothingChosenIsTheOldList() {
        List<ChannelPresets.Offer> l = ChannelPresets.offers(t.unchosen());
        List<ChannelPresets.Preset> old = ChannelPresets.all(t.unchosen());
        assertEquals(old.size(), l.size());
        for (int i = 0; i < l.size(); i++) { assertEquals(old.get(i).zone, l.get(i).preset.zone); assertEquals(ChannelPresets.NA, l.get(i).legal); assertFalse(l.get(i).hidden); }
    }

    @Test public void usKeepsItsLists() {
        List<ChannelPresets.Offer> l = ChannelPresets.offers(t.resolve("US"));
        for (String z : new String[] { "FRS", "GMRS", "MURS" }) assertEquals(z, ChannelPresets.LEGAL, offer(l, z).legal);
        assertEquals(ChannelPresets.NOT_LEGAL, offer(l, "PMR446").legal); assertEquals(ChannelPresets.NOT_LEGAL, offer(l, "UHF CB").legal);
        assertFalse(offer(l, "NOAA Weather").hidden); assertFalse(offer(l, "Business").hidden); assertFalse(offer(l, "Railroad").hidden);
        assertTrue("legal services first, not-legal ones after the rest", pos(l, "FRS") < pos(l, "Ham simplex") && pos(l, "Ham simplex") < pos(l, "PMR446"));
        assertEquals("the US ham list is the old one", names(ChannelPresets.ham(t.unchosen())), names(ChannelPresets.ham(t.resolve("US"))));
    }

    @Test public void belgiumOffersPmr446FirstAndHidesUsOnlyLists() {
        List<ChannelPresets.Offer> l = ChannelPresets.offers(t.resolve("BE"));
        assertEquals(ChannelPresets.LEGAL, offer(l, "PMR446").legal); assertEquals(0, pos(l, "PMR446"));
        for (String z : new String[] { "FRS", "GMRS", "MURS", "UHF CB" }) { assertEquals(z, ChannelPresets.NOT_LEGAL, offer(l, z).legal); assertFalse("not legal is listed, not hidden", offer(l, z).hidden); }
        assertTrue("no weather radio: under Show all", offer(l, "NOAA Weather").hidden);
        assertTrue(offer(l, "Business").hidden); assertTrue(offer(l, "Railroad").hidden);
        assertFalse(offer(l, "Ham simplex").hidden); assertFalse(offer(l, "ISS and satellites").hidden);
        int lastShown = -1, firstHidden = l.size(); for (int i = 0; i < l.size(); i++) if (l.get(i).hidden) firstHidden = Math.min(firstHidden, i); else lastShown = i;
        assertTrue("hidden lists come last", lastShown < firstHidden);
        List<String> ham = names(ChannelPresets.ham(t.resolve("BE")));
        assertEquals(java.util.Arrays.asList("2m Call@145500000", "APRS@144800000", "SSTV@433400000", "70cm Call@433500000"), ham);
    }

    @Test public void partlyLegalAndOtherRegions() {
        assertEquals("Canada: no GMRS repeater inputs", ChannelPresets.PARTLY, offer(ChannelPresets.offers(t.resolve("CA")), "GMRS").legal);
        assertEquals(ChannelPresets.LEGAL, offer(ChannelPresets.offers(t.resolve("CA")), "FRS").legal);
        assertFalse("railroad channels are of use in Canada", offer(ChannelPresets.offers(t.resolve("CA")), "Railroad").hidden);
        assertEquals("Argentina: FRS 1-7 only", ChannelPresets.PARTLY, offer(ChannelPresets.offers(t.resolve("AR")), "FRS").legal);
        List<ChannelPresets.Offer> au = ChannelPresets.offers(t.resolve("AU"));
        assertEquals(ChannelPresets.LEGAL, offer(au, "UHF CB").legal); assertEquals(0, pos(au, "UHF CB"));
        assertEquals(ChannelPresets.NOT_LEGAL, offer(au, "PMR446").legal);
    }

    @Test public void bandScopeWeatherPreset() {
        int wx = -1; for (int i = 0; i < BandScope.PRESETS.length; i++) if (BandScope.PRESETS[i].name.startsWith("NOAA")) wx = i;
        assertTrue(wx >= 0);
        assertTrue(BandScope.offered(t.resolve("US"), false).contains(wx)); assertTrue(BandScope.offered(t.unchosen(), false).contains(wx));
        assertFalse(BandScope.offered(t.resolve("BE"), false).contains(wx)); assertTrue("Show all", BandScope.offered(t.resolve("BE"), true).contains(wx));
        assertEquals(BandScope.PRESETS.length - 1, BandScope.offered(t.resolve("DE"), false).size());
    }

    @Test public void unitsFollowTheRegion() {
        Region.setForTest(t, t.resolve("US"));
        assertEquals("6.2 mi", GeoUtil.dist(10_000)); assertEquals("35 mph", GeoUtil.speed(56.3)); assertEquals("1000 ft", GeoUtil.alt(304.8)); assertEquals("15 mi", GeoUtil.radiusText(15));
        assertEquals(15 * 1.609344, GeoUtil.radiusKm(15), 1e-9); assertEquals("16.1mi", GeoUtil.formatDistance(25_900));
        Region.setForTest(t, t.resolve("BE"));
        assertEquals("10.0 km", GeoUtil.dist(10_000)); assertEquals("56 km/h", GeoUtil.speed(56.3)); assertEquals("305 m", GeoUtil.alt(304.8)); assertEquals("25 km", GeoUtil.radiusText(15));
        assertEquals(25, GeoUtil.radiusKm(15), 1e-9); assertEquals("25.9km", GeoUtil.formatDistance(25_900));
        Region.setForTest(t, t.resolve("GB"));
        assertFalse("the UK uses miles", Region.km());
        java.util.Map<String, Object> ov = new java.util.HashMap<>(); ov.put(Region.UNITS, "km");
        Region.setForTest(t, t.resolve("GB").withOverrides(ov));
        assertTrue("the user's own choice wins", Region.km());
    }
}
