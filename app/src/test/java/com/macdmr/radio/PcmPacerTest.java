/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import static org.junit.Assert.*;

import org.junit.Test;

/** Bursty microphone audio goes to the Xplore 1's module one 160-byte block per 10 ms (tester's recording, 2026-10-31). */
public class PcmPacerTest {
    private static final long MS = 1_000_000L;

    @Test public void burstsComeOutOneBlockEvery10ms() {
        PcmPacer p = new PcmPacer(8, 25);
        java.util.List<Long> out = new java.util.ArrayList<>();
        int seq = 0;
        for (long t = 0; t < 2000; t++) {                                   // 1 ms steps; the source delivers 64 ms at once, every 64 ms
            if (t % 64 == 0) { byte[] b = new byte[1024]; for (int i = 0; i < b.length; i++) b[i] = (byte) seq++; p.offer(b, 0, b.length); }
            byte[] blk = p.poll(t * MS);
            if (blk != null) { assertEquals(160, blk.length); out.add(t); }
        }
        assertTrue("prebuffered before the first block", out.get(0) >= 64);
        for (int i = 1; i < out.size(); i++) assertEquals("steady 10 ms", 10, out.get(i) - out.get(i - 1));
        assertEquals(0, p.underruns); assertEquals(0, p.dropped);
    }

    @Test public void blocksKeepTheirBytesInOrder() {
        PcmPacer p = new PcmPacer(1, 25);
        byte[] a = new byte[100], b = new byte[220];
        for (int i = 0; i < a.length; i++) a[i] = (byte) i;
        for (int i = 0; i < b.length; i++) b[i] = (byte) (100 + i);
        p.offer(a, 0, a.length); p.offer(b, 0, b.length);                   // 320 bytes = two blocks
        byte[] x = p.poll(0), y = p.poll(10 * MS);
        for (int i = 0; i < 160; i++) { assertEquals((byte) i, x[i]); assertEquals((byte) (160 + i), y[i]); }
        assertNull(p.poll(20 * MS));
    }

    @Test public void underrunFillsUpAgainAndBacklogStaysShort() {
        PcmPacer p = new PcmPacer(2, 4);
        p.offer(new byte[320], 0, 320);
        assertNotNull(p.poll(0)); assertNotNull(p.poll(10 * MS));
        assertNull(p.poll(20 * MS)); assertEquals(1, p.underruns);
        p.offer(new byte[160], 0, 160); assertNull("waits for the prebuffer", p.poll(30 * MS));
        p.offer(new byte[160 * 10], 0, 160 * 10);                          // a long stall's backlog
        assertEquals(7, p.dropped);                                         // 11 queued, 4 kept
        assertNotNull(p.poll(40 * MS)); assertNull(p.poll(45 * MS)); assertNotNull(p.poll(50 * MS));
    }

    @Test public void aLateWriterDoesNotBurstToCatchUp() {
        PcmPacer p = new PcmPacer(1, 25);
        p.offer(new byte[160 * 10], 0, 160 * 10);
        assertNotNull(p.poll(0));
        assertNotNull(p.poll(100 * MS));                                    // the writer slept 100 ms
        assertNull("no second block straight away", p.poll(101 * MS));
        assertNotNull(p.poll(110 * MS));
    }

    @Test public void sessionOffersOnlyChannelsOfTheKindAStepNeeds() {
        java.util.List<ChannelStore.Channel> all = new java.util.ArrayList<>();
        for (int t : new int[] { ChannelStore.Channel.TYPE_DMR, 1, ChannelStore.Channel.TYPE_M17, ChannelStore.Channel.TYPE_DMR }) {
            ChannelStore.Channel c = new ChannelStore.Channel(); c.type = t; c.number = all.size() + 1; c.name = "C" + c.number; c.txFreq = 446_006_250; all.add(c);
        }
        java.util.List<ChannelStore.Channel> dmr = TxTestSession.channelsOfKind(all, true), fm = TxTestSession.channelsOfKind(all, false);
        assertEquals(2, dmr.size()); assertEquals(1, dmr.get(0).number); assertEquals(4, dmr.get(1).number);
        assertEquals(1, fm.size()); assertEquals(2, fm.get(0).number);
        assertTrue(TxTestSession.channelRow(all.get(0)).contains("446.006 MHz"));
    }
}
