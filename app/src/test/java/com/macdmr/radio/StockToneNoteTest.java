/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class StockToneNoteTest {
    private static ChannelStore.Channel fm(int rxType, int code) { ChannelStore.Channel c = new ChannelStore.Channel(); c.type = ChannelStore.Channel.TYPE_FM; c.rxType = rxType; c.rxSubCode = code; return c; }

    @Test public void factoryToneOnEveryChannelIsSaid() {
        List<ChannelStore.Channel> l = new ArrayList<>();
        for (int i = 0; i < 5; i++) l.add(fm(1, 0));                    // the W6Plus DMR's factory channels: CTCSS code 0 = 62.5 Hz
        ChannelStore.Channel d = new ChannelStore.Channel(); d.type = ChannelStore.Channel.TYPE_DMR; l.add(d);
        String s = RadioService.toneNote(l);
        assertNotNull(s);
        assertTrue(s, s.startsWith("All 5 imported analog channels") && s.contains("62.5"));
    }

    @Test public void someChannelsAndNone() {
        List<ChannelStore.Channel> l = new ArrayList<>();
        l.add(fm(0, 0)); l.add(fm(1, 0)); l.add(fm(2, 23));
        assertTrue(RadioService.toneNote(l).startsWith("2 of 3 imported analog channels"));
        List<ChannelStore.Channel> none = new ArrayList<>(); none.add(fm(0, 0));
        assertNull(RadioService.toneNote(none));
    }
}
