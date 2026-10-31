/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.web;

import static org.junit.Assert.*;

import com.macdmr.radio.region.RegionTable;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** The radio-state JSON carries the region's values; the pages take the APRS frequency from it, not from a literal. */
public class WebRegionTest {
    private static String read(String p) throws Exception { return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8); }

    @Test public void regionJson() throws Exception {
        RegionTable t = RegionTable.parse(read("src/main/assets/regions/regions.json"));
        JSONObject be = WebHost.regionJson(t.resolve("BE"));
        assertEquals("BE", be.getString("id")); assertEquals(144_800_000, be.getInt("aprsHz")); assertEquals("km", be.getString("units")); assertFalse(be.getBoolean("weatherRadio")); assertTrue(be.getBoolean("chosen"));
        JSONObject none = WebHost.regionJson(t.unchosen());
        assertFalse(none.getBoolean("chosen")); assertEquals(144_390_000, none.getInt("aprsHz")); assertEquals("mi", none.getString("units"));
    }

    @Test public void pagesHaveNoHardCodedAprsFrequency() throws Exception {
        String html = read("src/main/assets/web/index.html"), js = read("src/main/assets/web/app.js");
        assertFalse(html.contains("144.390")); assertFalse(html.contains("aprs144"));
        assertFalse(js.contains("aprs144"));
        assertTrue(js.contains("rd.aprsTerrestrial") && js.contains("rd.aprsHz"));
    }
}
