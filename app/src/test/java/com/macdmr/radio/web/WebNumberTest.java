/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.web;

import static org.junit.Assert.*;

import com.macdmr.radio.ChannelStore;

import org.json.JSONObject;
import org.junit.Test;

/**
 * Numbers from SignalDeck Web: a decimal comma ("439,9875") is read as the phone reads it, anything malformed is
 * refused. Never truncated: parseFloat("439,9875") is 439, which once sent a page out on 439.000 MHz.
 */
public class WebNumberTest {
    private static JSONObject o(String k, Object v) throws Exception { return new JSONObject().put(k, v); }

    @Test public void aCommaIsReadAndGarbageRefused() throws Exception {
        assertEquals(439.9875, WebHost.number(o("f", "439,9875"), "f"), 1e-9);
        assertEquals(439.9875, WebHost.number(o("f", " 439.9875 "), "f"), 1e-9);
        assertEquals(446.00625, WebHost.number(o("f", 446.00625), "f"), 1e-9);
        assertNull(WebHost.number(o("f", ""), "f"));
        assertNull(WebHost.number(o("f", JSONObject.NULL), "f"));
        assertNull(WebHost.number(new JSONObject(), "f"));
        for (String bad : new String[] { "439,98,75", "1.234,5", "1,234.5", "12a", "12f", "1e3", "NaN", "439 MHz" })
            try { WebHost.number(o("f", bad), "f"); fail(bad + " was read"); } catch (NumberFormatException expected) { }
    }

    @Test public void aPageNeverGoesOutOnATruncatedFrequency() throws Exception {
        assertEquals(439_987_500, WebHost.pageHz(o("hz", 439_987_500), 467_625_000));
        assertEquals(439_987_500, WebHost.pageHz(o("hz", "439987500"), 467_625_000));
        assertEquals(467_625_000, WebHost.pageHz(new JSONObject(), 467_625_000));   // no frequency: the channel's
        assertEquals(467_625_000, WebHost.pageHz(o("hz", 0), 467_625_000));           // the Messages page with no channel
        for (Object bad : new Object[] { "439,9875", 439.9875, "439.000,5", "abc", -1, 5e9 })
            try { WebHost.pageHz(o("hz", bad), 467_625_000); fail(bad + " was sent"); } catch (NumberFormatException expected) { }
    }

    @Test public void aChannelSavedWithACommaKeepsItsFrequency() throws Exception {
        ChannelStore.Channel ch = new ChannelStore.Channel();
        JSONObject b = new JSONObject().put("name", "Comma").put("type", 0).put("rxMHz", "446,00625").put("txMHz", "446,00625").put("lat", "47,6062").put("lon", "-122,3321");
        assertNull(WebHost.applyChannel(ch, b));
        assertEquals(446_006_250, ch.rxFreq);
        assertEquals(446_006_250, ch.txFreq);
        assertEquals(47.6062, ch.lat, 1e-9);
        assertEquals(-122.3321, ch.lon, 1e-9);

        ChannelStore.Channel bad = new ChannelStore.Channel();
        String err = WebHost.applyChannel(bad, new JSONObject().put("type", 0).put("rxMHz", 446.00625).put("txMHz", "446,00,625"));
        assertNotNull("a malformed send frequency must be refused, not made simplex", err);
        assertTrue(err, err.startsWith("Check the numbers"));
    }

    @Test public void thePagesReadNumbersWithTheSharedHelper() throws Exception {
        java.io.File web = WebI18nTest.web();
        String app = WebI18nTest.read(new java.io.File(web, "app.js"));
        assertTrue("app.js defines num()", app.contains("function num(s)"));
        for (String name : new String[] { "app.js", "editor.js", "pages.js", "talk.js", "audio.js", "exam.js", "morse.js" }) {
            String js = WebI18nTest.read(new java.io.File(web, name));
            assertFalse(name + ": parseFloat stops at a decimal comma; use num()", js.contains("parseFloat("));
        }
    }
}
