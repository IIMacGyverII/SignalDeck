/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** A call the promiscuous firmware admits outside the channel's lists arrives as status 10 / 11. */
public class PromiscuousStatusTest {
    @Test public void mixedDigitalIsAReceptionOnADigitalChannel() {
        assertEquals(1, RadioService.rxStatusFor(10, true));
        assertEquals(2, RadioService.rxStatusFor(11, true));
        assertEquals("plain receive start unchanged", 1, RadioService.rxStatusFor(1, true));
        assertEquals("send stop unchanged", 4, RadioService.rxStatusFor(4, true));
    }

    @Test public void analogChannelsKeepTheirCodes() {
        assertEquals(10, RadioService.rxStatusFor(10, false));
        assertEquals(11, RadioService.rxStatusFor(11, false));
        assertEquals(12, RadioService.rxStatusFor(12, true));
    }
}
