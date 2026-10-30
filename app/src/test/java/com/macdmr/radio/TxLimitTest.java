/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Device → Longest transmission: the setting → SignalDeck's limit, the module timer byte, the heat warning, the test session's override. */
public class TxLimitTest {

    @Test public void defaultIsTwoMinutes() {
        assertEquals(120, TxLimit.valid(-1));                  // never set
        assertEquals(120_000L, TxLimit.limitMs(-1));
        assertEquals(2, TxLimit.presetIndex(TxLimit.DEFAULT_S));
    }

    @Test public void limitMs() {
        assertEquals(30_000L, TxLimit.limitMs(30));
        assertEquals(600_000L, TxLimit.limitMs(600));
        assertEquals(-1L, TxLimit.limitMs(TxLimit.NONE));      // no SignalDeck limit
        assertEquals(10_000L, TxLimit.limitMs(3));             // below the custom minimum: raised to 10 s
        assertEquals(Integer.MAX_VALUE, TxLimit.capMs(TxLimit.NONE));
        assertEquals(95_000, TxLimit.capMs(95));
    }

    @Test public void moduleTimerByte() {
        assertEquals(122, TxLimit.moduleTot(120));             // 2 s past SignalDeck's own limit
        assertEquals(32, TxLimit.moduleTot(30));
        assertEquals(12, TxLimit.moduleTot(10));
        assertEquals(182, TxLimit.moduleTot(180));
        assertEquals(255, TxLimit.moduleTot(253));
        assertEquals(255, TxLimit.moduleTot(254));             // capped at the byte's 255
        assertEquals(255, TxLimit.moduleTot(255));
        assertEquals(0, TxLimit.moduleTot(256));               // over 255: timer off, SignalDeck's watchdog alone
        assertEquals(0, TxLimit.moduleTot(300));
        assertEquals(0, TxLimit.moduleTot(600));
        assertEquals(0, TxLimit.moduleTot(TxLimit.NONE));
        assertEquals(122, TxLimit.moduleTot(-1));              // never set = the default
        for (int s = TxLimit.MIN_CUSTOM_S; s <= 255; s++) { int b = TxLimit.moduleTot(s); assertTrue(b >= s && b <= 255); }
    }

    @Test public void heatWarning() {
        assertFalse(TxLimit.needsWarning(30));
        assertFalse(TxLimit.needsWarning(60));
        assertFalse(TxLimit.needsWarning(120));
        assertTrue(TxLimit.needsWarning(121));
        assertTrue(TxLimit.needsWarning(180));
        assertTrue(TxLimit.needsWarning(600));
        assertTrue(TxLimit.needsWarning(TxLimit.NONE));
        assertFalse(TxLimit.needsWarning(-1));                 // the default
    }

    @Test public void testSessionOverride() {
        assertEquals(28, TxLimit.effective(TxLimit.NONE, TxLimit.TEST_SESSION_S));
        assertEquals(28, TxLimit.effective(600, TxLimit.TEST_SESSION_S));
        assertEquals(28, TxLimit.effective(10, TxLimit.TEST_SESSION_S));
        assertEquals(30, TxLimit.moduleTot(TxLimit.effective(10, TxLimit.TEST_SESSION_S)));   // the module timer follows, so it never ends the check's key first
        assertEquals(600, TxLimit.effective(600, 0));          // no override: the setting
        assertEquals(TxLimit.NONE, TxLimit.effective(TxLimit.NONE, 0));
        assertEquals(120, TxLimit.effective(-1, 0));
    }

    @Test public void namesAndCustom() {
        assertEquals("30 s", TxLimit.name(30));
        assertEquals("1 min", TxLimit.name(60));
        assertEquals("2 min", TxLimit.name(120));
        assertEquals("1 min 30 s", TxLimit.name(90));
        assertEquals("10 min", TxLimit.name(600));
        assertEquals("No limit", TxLimit.name(TxLimit.NONE));
        assertEquals(45, TxLimit.parseCustom(" 45 "));
        assertEquals(10, TxLimit.parseCustom("10"));
        assertEquals(-1, TxLimit.parseCustom("9"));
        assertEquals(-1, TxLimit.parseCustom("0"));
        assertEquals(-1, TxLimit.parseCustom("abc"));
        assertEquals(-1, TxLimit.presetIndex(95));
        assertEquals(6, TxLimit.presetIndex(TxLimit.NONE));
    }
}
