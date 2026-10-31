/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import static org.junit.Assert.*;

import org.junit.Test;

/** Promiscuous receive: the module reports every call as 10 / 11 while the flag is on; on a digital channel they are receptions. */
public class PromiscuousStatusTest {
    @Test public void mixStatusesAreReceptionsOnADigitalChannel() {
        assertEquals(1, RadioService.rxStatusFor(10, true));
        assertEquals(2, RadioService.rxStatusFor(11, true));
    }

    @Test public void otherStatusesAndAnalogChannelsUnchanged() {
        assertEquals(10, RadioService.rxStatusFor(10, false));
        assertEquals(11, RadioService.rxStatusFor(11, false));
        for (int c : new int[] { 1, 2, 3, 4, 6, 8, 9 }) { assertEquals(c, RadioService.rxStatusFor(c, true)); assertEquals(c, RadioService.rxStatusFor(c, false)); }
    }
}
