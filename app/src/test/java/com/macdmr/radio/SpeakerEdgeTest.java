/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import static org.junit.Assert.*;

import org.junit.Test;

/** FW57's speaker switch posts 0x36 1 / 2 on its own edges; on the Armor those that follow the app's own command are not receptions. */
public class SpeakerEdgeTest {
    private static final long T = 1_000_000L;

    @Test public void openEdgeRightAfterTheReopenIsIgnored() {
        assertTrue(RadioService.ownSpeakerEdge(1, true, true, false, T + 780, T));    // bench: the last 01 came 0.78 s after 0x2F
        assertTrue(RadioService.ownSpeakerEdge(2, true, true, true, T + 40, T));     // the close edge of a channel write
    }

    @Test public void aCallLaterIsAReception() {
        assertFalse(RadioService.ownSpeakerEdge(1, true, true, false, T + 5_000, T));
        assertFalse(RadioService.ownSpeakerEdge(2, true, true, true, T + 9_000, T));
    }

    @Test public void aStopWithoutAStartEndsNothing() {
        assertTrue(RadioService.ownSpeakerEdge(2, true, true, false, T + 60_000, T));
    }

    @Test public void onlyFlaggedDigitalChannels() {
        assertFalse(RadioService.ownSpeakerEdge(1, false, true, false, T + 100, T));   // other phones: unchanged
        assertFalse(RadioService.ownSpeakerEdge(1, true, false, false, T + 100, T));   // analog: unchanged
        assertFalse(RadioService.ownSpeakerEdge(3, true, true, false, T + 100, T));    // transmit statuses untouched
        assertFalse(RadioService.ownSpeakerEdge(10, true, true, false, T + 100, T));
    }
}
