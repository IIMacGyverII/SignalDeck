/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Arrays;

public class RoamingTest {
    private static ChannelStore.Channel dmr(int n, double lat, double lon, int tg, int... rx) {
        ChannelStore.Channel c = new ChannelStore.Channel(); c.number = n; c.type = 0; c.lat = lat; c.lon = lon; c.txContact = tg; c.contactType = 1; c.groups = rx; c.name = "R" + n; c.rxFreq = 439_000_000 + n * 12_500; c.txFreq = c.rxFreq - 5_000_000; return c;
    }
    @Test public void switchesOnlyWhenClearlyNearer() {
        // three repeaters along a line in England (unrelated test places), 0.1° of latitude ≈ 11 km
        ChannelStore.Channel a = dmr(1, 51.0, -2.0, 3100), b = dmr(2, 51.5, -2.0, 3100), far = dmr(3, 52.5, -2.0, 3100), other = dmr(4, 51.45, -2.0, 91);
        assertTrue("test channels must be DMR", a.isDigital());
        // at 51.45: a is 50 km away, b 5.6 km → switch to b; "other" is nearer but another talkgroup
        assertSame(b, Roaming.choose(a, Arrays.asList(a, b, far, other), 51.45, -2.0));
        // half way (51.25): a 28 km, b 28 km → stay (no flip-flopping)
        assertNull(Roaming.choose(a, Arrays.asList(a, b, far), 51.25, -2.0));
        // the talkgroup in a candidate's RX list counts too
        ChannelStore.Channel listed = dmr(5, 51.5, -2.01, 9, 3100);
        assertSame(listed, Roaming.choose(a, Arrays.asList(a, listed), 51.5, -2.0));
        // no location on the current channel, or an analog channel: never
        ChannelStore.Channel noLoc = dmr(6, 0, 0, 3100); assertNull(Roaming.choose(noLoc, Arrays.asList(noLoc, b), 51.5, -2.0));
    }
}
