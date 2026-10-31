/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import static org.junit.Assert.*;

import com.macdmr.radio.pkt.Ax25;
import com.macdmr.radio.pkt.Ax25Session;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PacketFinderTest {
    static byte[] ui(String dst, String src, int pid, byte[] info) {
        byte[] f = Ax25.buildUiFrame(dst, 0, src.replaceAll("-.*", ""), src.contains("-") ? Integer.parseInt(src.replaceAll(".*-", "")) : 0, new String[0], info);
        f = Arrays.copyOf(f, f.length - 2);                                     // buildUiFrame adds the FCS
        int off = 14; f[off + 1] = (byte) pid;
        return f;
    }

    @Test public void netromBroadcastIsANode() {
        byte[] info = new byte[1 + 6 + 21 * 2]; info[0] = (byte) 0xFF; System.arraycopy("NODE1 ".getBytes(StandardCharsets.US_ASCII), 0, info, 1, 6);
        List<PacketFinder.Hit> h = PacketFinder.classify(ui("NODES", "N0NOD-1", 0xCF, info));
        assertEquals(1, h.size()); assertEquals("N0NOD-1", h.get(0).call); assertEquals(PacketFinder.NODE, h.get(0).kind);
        assertTrue(h.get(0).note, h.get(0).note.contains("NODE1") && h.get(0).note.contains("2 routes"));
    }

    @Test public void beaconsAndIds() {
        List<PacketFinder.Hit> h = PacketFinder.classify(ui("ID", "N0BBS-1", 0xF0, "N0BBS-1/R BBS Anytown\r".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(PacketFinder.BBS, h.get(0).kind);
        h = PacketFinder.classify(ui("BEACON", "N0RMS-10", 0xF0, "Winlink RMS packet gateway, 1200 baud".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(PacketFinder.GATEWAY, h.get(0).kind);
        h = PacketFinder.classify(ui("ID", "N0XYZ", 0xF0, "N0XYZ".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(PacketFinder.ID, h.get(0).kind);
        h = PacketFinder.classify(ui("MAIL", "N0BBS-1", 0xF0, "Mail for: N0ABC N0DEF".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(PacketFinder.BBS, h.get(0).kind);
    }

    @Test public void aprsIsLeftToTheAprsPage() {
        assertTrue(PacketFinder.classify(ui("APDR16", "N0CALL-7", 0xF0, "!4903.50N/07201.75W-Test".getBytes(StandardCharsets.US_ASCII))).isEmpty());
        assertTrue(PacketFinder.classify(ui("S32U6T", "N0CALL-9", 0xF0, "`(_fn\"Oj/]".getBytes(StandardCharsets.US_ASCII))).isEmpty());   // Mic-E
        assertTrue(PacketFinder.classify(new byte[] { 1, 2, 3 }).isEmpty());
    }

    @Test public void connectedTrafficAndBbsPrompt() {
        List<byte[]> out = new ArrayList<>();
        Ax25Session bbs = new Ax25Session("N0BBS", 1, new Ax25Session.Link() {
            @Override public void send(List<byte[]> frames) { out.addAll(frames); }
            @Override public void received(String text) { }
            @Override public void state(String s) { }
        });
        bbs.setAcceptIncoming(true);
        List<byte[]> sabm = new ArrayList<>();
        Ax25Session user = new Ax25Session("N0USR", 0, new Ax25Session.Link() {
            @Override public void send(List<byte[]> frames) { sabm.addAll(frames); }
            @Override public void received(String text) { }
            @Override public void state(String s) { }
        });
        user.connect("N0BBS-1", new String[0], 0);
        List<PacketFinder.Hit> h = PacketFinder.classify(sabm.get(0));
        assertEquals("N0USR", h.get(0).call); assertEquals(PacketFinder.STATION, h.get(0).kind); assertTrue(h.get(0).note.contains("N0BBS-1"));
        bbs.receive(sabm.get(0), 0); bbs.sendText("[FBB-7.00-AB1FHMRX$]\rHello, this is N0BBS BBS\r>\r", 0);
        byte[] iFrame = out.get(out.size() - 1);
        h = PacketFinder.classify(iFrame);
        assertEquals("N0BBS-1", h.get(0).call); assertEquals(PacketFinder.BBS, h.get(0).kind);
    }

    @Test public void mergeKeepsTheBestKindAndSignal() {
        Map<String, PacketFinder.Station> m = new LinkedHashMap<>();
        PacketFinder.merge(m, new PacketFinder.Hit("N0BBS-1", PacketFinder.STATION, "in a connection with N0USR"), 145_010_000, -100, 1000);
        PacketFinder.merge(m, new PacketFinder.Hit("N0BBS-1", PacketFinder.BBS, "N0BBS BBS"), 145_030_000, -80, 2000);
        PacketFinder.merge(m, new PacketFinder.Hit("N0BBS-1", PacketFinder.STATION, "x"), 145_050_000, -95, 3000);
        PacketFinder.Station s = m.get("N0BBS-1");
        assertEquals(PacketFinder.BBS, s.kind); assertEquals("N0BBS BBS", s.note); assertEquals(-80, s.bestDbm); assertEquals(145_030_000, s.hz); assertEquals(3, s.count); assertEquals(3000, s.last);
    }

    @Test public void frequencyListTakesSemicolonsAndADecimalComma() {
        // the German hint: semicolons, a comma as the decimal sign
        assertEquals(Arrays.asList("144,910", "145,010"), PacketFinder.listItems("144,910; 145,010"));
        assertEquals(Arrays.asList("144,910", "145,010"), PacketFinder.listItems("144,910;145,010"));
        assertEquals(Arrays.asList("144,910", "145.010"), PacketFinder.listItems("144,910 145.010"));
        // the English form from before keeps working: with a point, commas separate
        assertEquals(Arrays.asList("144.390", "145.825"), PacketFinder.listItems("144.390, 145.825"));
        assertEquals(Arrays.asList("144.390", "145.825"), PacketFinder.listItems("144.390,145.825"));
        assertEquals(Arrays.asList("145", "146.52"), PacketFinder.listItems("145, 146.52"));     // myChannelsText's own form
        assertEquals(Arrays.asList("144.39", "145.01"), PacketFinder.listItems("144.39;145.01"));  // as stored now
        assertEquals(Arrays.asList(), PacketFinder.listItems("  ,; "));
        // several commas and no point: unreadable either way, kept whole so the error names it as typed
        assertEquals(Arrays.asList("144,910,145,010"), PacketFinder.listItems("144,910,145,010"));
        double[] want = { 144.910, 145.010 }; List<String> got = PacketFinder.listItems("144,910; 145,010");
        for (int i = 0; i < want.length; i++) assertEquals(want[i], com.macdmr.radio.i18n.Txt.parseDecimal(got.get(i)), 1e-9);
        assertEquals("144.91", PacketFinder.storedMhz(144_910_000));
        assertEquals("145", PacketFinder.storedMhz(145_000_000));
    }
}
