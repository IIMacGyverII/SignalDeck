/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.region;

import static org.junit.Assert.*;

import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/** The region model: the default-region order, fallbacks to the IARU defaults, the user's own values, and the band edges. */
public class RegionTest {
    static RegionTable t;
    @BeforeClass public static void load() throws Exception { t = RegionTable.parse(new String(Files.readAllBytes(Paths.get("src/main/assets/regions/regions.json")), StandardCharsets.UTF_8)); }

    @Test public void defaultOrderSimThenNetworkThenLocale() {
        assertArrayEquals(new String[] { "BE", "sim", null }, Region.derive("be", "be", "US", RegionTable.isoCountries()));
        assertArrayEquals("roaming: the SIM wins, the network is noted", new String[] { "BE", "sim", "FR" }, Region.derive("be", "fr", "US", RegionTable.isoCountries()));
        assertArrayEquals(new String[] { "FR", "network", null }, Region.derive("", "fr", "US", RegionTable.isoCountries()));
        assertArrayEquals(new String[] { "DE", "locale", null }, Region.derive(null, null, "DE", RegionTable.isoCountries()));
        assertArrayEquals("junk is skipped", new String[] { "JP", "locale", null }, Region.derive("x", "123", "jp", RegionTable.isoCountries()));
        assertNull("nothing usable: ask", Region.derive(null, "", "ZZ", RegionTable.isoCountries()));
    }

    @Test public void countriesAndFallbacks() {
        Region be = t.resolve("BE");
        assertEquals(144_800_000, be.aprsHz); assertEquals(145_500_000, be.call2mHz); assertTrue(be.km); assertFalse(be.weatherRadio); assertFalse(be.generic);
        Region kr = t.resolve("KR");
        assertEquals("a null APRS frequency is the IARU default's", t.resolve("iaru3").aprsHz, kr.aprsHz);
        Region lu = t.resolve("LU");                                   // no entry of its own
        assertTrue(lu.generic); assertEquals("Luxembourg", lu.name); assertEquals(1, lu.iaru); assertEquals(144_800_000, lu.aprsHz);
        assertEquals(2, t.resolve("CL").iaru); assertEquals(3, t.resolve("SG").iaru);
        assertNull(t.resolve("QQ")); assertNull(t.resolve(null));
        Region cn = t.resolve("CN");
        assertEquals("a country's own APRS frequency does not inherit the default's second one", 0, cn.aprsAltHz);
    }

    /** Belgium after ON4WX's source review (issue #95, 2026-10-30). */
    @Test public void belgiumReviewed() {
        Region be = t.resolve("BE");
        assertEquals(144_800_000, be.aprsHz); assertEquals(145_500_000, be.call2mHz); assertEquals(433_500_000, be.call70Hz);
        assertEquals("FM SSTV is 433.400 (70 cm plan); 144.500 is the SSB image-mode centre", 433_400_000, be.sstvHz);
        assertEquals(439_987_500, be.dapnetHz); assertFalse(be.unconfirmed("dapnetHz", null));
        assertFalse(be.unconfirmed("callingHz", "70cm")); assertFalse(be.unconfirmed("sstvHz", null));
        assertFalse(be.unconfirmed("licenseFree", "PMR446")); assertTrue(be.unconfirmed("licenseFree", "LPD433"));
        assertEquals("no station-ID interval is inferred", 0, be.stationIdMinutes);
        assertTrue(be.amateur(144_000_000)); assertTrue(be.amateur(440_000_000)); assertFalse(be.amateur(146_520_000));
        assertTrue("the Region 1 default's DAPNET is a national note", t.resolve("iaru1").unconfirmed("dapnetHz", null));
        assertTrue("a country without its own DAPNET value keeps the default's mark", t.resolve("LU").unconfirmed("dapnetHz", null));
    }

    @Test public void usMatchesTheOldBehaviour() {
        Region us = t.resolve("US"), old = t.unchosen();
        assertFalse(old.chosen); assertTrue(us.chosen);
        assertEquals(old.aprsHz, us.aprsHz); assertEquals(144_390_000, us.aprsHz);
        assertEquals(old.call2mHz, us.call2mHz); assertEquals(old.call70Hz, us.call70Hz);
        assertEquals(old.weatherRadio, us.weatherRadio); assertEquals(old.km, us.km); assertFalse(us.km);
        assertEquals(10 * 60_000L, us.idEveryMs()); assertEquals(10 * 60_000L, old.idEveryMs());
        for (long hz : new long[] { 144_000_000L, 146_520_000L, 148_000_000L, 420_000_000L, 446_000_000L, 450_000_000L, 143_990_000L, 151_820_000L, 462_562_500L })
            assertEquals("edge " + hz, old.amateur(hz), us.amateur(hz));
    }

    @Test public void bandEdgesPerRegion() {
        assertTrue(t.resolve("US").amateur(146_520_000)); assertFalse(t.resolve("BE").amateur(146_520_000));
        assertTrue(t.resolve("BE").amateur(145_500_000)); assertFalse(t.resolve("BE").amateur(445_000_000)); assertTrue(t.resolve("US").amateur(445_000_000));
        assertFalse("Italy has a hole at 434-435 MHz", t.resolve("IT").amateur(434_500_000));
        assertTrue(t.unchosen().amateur(223_500_000));
    }

    @Test public void overridesWinAndBadOnesAreSkipped() {
        Map<String, Object> ov = new HashMap<>();
        ov.put(Region.APRS, "144825000"); ov.put(Region.UNITS, "mi"); ov.put(Region.STATION_ID, "15"); ov.put(Region.CALL70, "0");
        Region r = t.resolve("BE").withOverrides(ov);
        assertEquals(144_825_000, r.aprsHz); assertFalse(r.km); assertEquals(15, r.stationIdMinutes); assertEquals(0, r.call70Hz);
        assertTrue(r.overridden.contains(Region.APRS) && r.overridden.contains(Region.UNITS) && r.overridden.size() == 4);
        assertEquals("the base is untouched", 144_800_000, t.resolve("BE").aprsHz);
        Map<String, Object> bad = new HashMap<>();
        bad.put(Region.APRS, "0"); bad.put(Region.UNITS, "furlongs"); bad.put(Region.STATION_ID, "99"); bad.put(Region.SSTV, "abc");
        Region b = t.resolve("BE").withOverrides(bad);
        assertEquals(144_800_000, b.aprsHz); assertTrue(b.km); assertTrue(b.overridden.isEmpty());
        Map<String, Object> wx = new HashMap<>(); wx.put(Region.WEATHER, "true");
        assertTrue(t.resolve("BE").withOverrides(wx).weatherRadio);
    }

    @Test public void pickList() {
        java.util.List<String[]> l = t.pickList();
        assertTrue(l.size() > 200);
        assertEquals("iaru3", l.get(l.size() - 1)[0]);
        boolean be = false; for (String[] e : l) if (e[0].equals("BE")) { be = true; assertEquals("Belgium", e[1]); }
        assertTrue(be);
    }
}
