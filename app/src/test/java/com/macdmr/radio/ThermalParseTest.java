/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.macdmr.radio;

import static org.junit.Assert.*;

import org.junit.Test;

/** The thermal zone's text, read directly or over root, in degrees C. */
public class ThermalParseTest {
    @Test public void millidegreesDegreesAndJunk() {
        assertEquals(45.3, ThermalGuard.parseC("45300"), 1e-9);
        assertEquals(45.3, ThermalGuard.parseC(" 45300\n"), 1e-9);
        assertEquals(52.0, ThermalGuard.parseC("52"), 1e-9);
        assertTrue(Double.isNaN(ThermalGuard.parseC(null)));
        assertTrue(Double.isNaN(ThermalGuard.parseC("su failed: x")));
    }

    @Test public void interruptedThreadStaysInterruptedAfterARootCall() throws Exception {
        final boolean[] flag = new boolean[1];
        Thread t = new Thread(() -> { Thread.currentThread().interrupt(); RootSetup.run("true"); flag[0] = Thread.currentThread().isInterrupted(); });
        t.start(); t.join(5000);
        assertTrue("an interrupt must survive RootSetup.run", flag[0]);
    }
}
