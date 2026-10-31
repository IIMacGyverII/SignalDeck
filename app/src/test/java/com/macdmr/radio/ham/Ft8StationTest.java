/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.ham;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class Ft8StationTest {
    /** A fake radio: a settable clock, transmissions recorded and decoded back to text. */
    static class FakeHost implements Ft8Station.Host {
        long now = 1_700_000_000_000L - 1_700_000_000_000L % 15000;     // a slot boundary
        final List<String> tx = new ArrayList<>(), events = new ArrayList<>(), logged = new ArrayList<>();
        final List<Long> txAt = new ArrayList<>();
        short[] lastPcm;
        @Override public long now() { return now; }
        @Override public boolean canTransmit() { return true; }
        @Override public void transmit(short[] pcm, String label, Runnable done) {
            lastPcm = pcm; txAt.add(now);
            float[] x = new float[pcm.length]; for (int i = 0; i < x.length; i++) x[i] = pcm[i] / 32768f;
            List<Ft8.Decode> d = Ft8.decode(padToSlot(x), Ft8.RATE * 15, new Ft8Message.Hashes());
            tx.add(d.isEmpty() ? "?" : d.get(0).text);
            done.run();
        }
        float[] padToSlot(float[] x) {                                   // where the audio lands in its slot: it plays txLatencyMs after keying
            long key = txAt.get(txAt.size() - 1), boundary = (key / 15000 + 1) * 15000;
            int shift = (int) ((boundary - key - Ft8Station.txLatencyMs) * Ft8.RATE / 1000);
            float[] s = new float[Ft8.RATE * 15];
            for (int i = Math.max(0, shift); i < x.length && i - shift < s.length; i++) s[i - shift] = x[i];
            return s;
        }
        @Override public String callsign() { return "W9XYZ"; }
        @Override public String grid() { return "DM79"; }
        @Override public void event(String line) { events.add(line); }
        @Override public void heard(String call, String grid, int snr) { }
        @Override public void logQso(String call, String grid, String sent, String rcvd) { logged.add(call + " " + grid + " " + sent + " " + rcvd); }
        @Override public void decodeAsync(Runnable job) { job.run(); }
    }

    private static Ft8.Decode d(String text, int snr) {
        float[] x = new float[Ft8.RATE * 15];
        float[] w = Ft8.synth(Ft8.tones(Ft8Message.pack(text, null)), 1200, Ft8.RATE);
        for (int i = 0; i < w.length; i++) x[Ft8.RATE / 2 + i] = 0.1f * w[i];
        List<Ft8.Decode> l = Ft8.decode(x, x.length, new Ft8Message.Hashes());
        Ft8.Decode o = l.get(0);
        return new Ft8.Decode(o.freqHz, o.dtS, snr, o.payload, o.text);
    }

    /** Run the station's scheduler through one slot boundary and return what it sent, if anything. */
    private static String nextTx(FakeHost h, Ft8Station st) {
        int before = h.tx.size();
        long end = (h.now / 15000 + 2) * 15000;
        for (; h.now < end; h.now += 250) { st.tick(); if (h.tx.size() > before) { h.now = (h.now / 15000 + 1) * 15000; return h.tx.get(before); } }
        return null;
    }

    /** Feed {@code x} from {@code fromS} to {@code toS} seconds into the current slot, in 0.25 s blocks as a capture delivers them. */
    private static void feed(FakeHost h, Ft8Station st, float[] x, double fromS, double toS) {
        long slot0 = h.now - h.now % 15000; int blk = Ft8.RATE / 4;
        for (int a = (int) (fromS * Ft8.RATE); a + blk <= (int) (toS * Ft8.RATE); a += blk) {
            byte[] b = new byte[blk * 2];
            for (int i = 0; i < blk; i++) { int v = Math.round(x[a + i] * 32767); b[2 * i] = (byte) v; b[2 * i + 1] = (byte) (v >> 8); }
            h.now = slot0 + (a + blk) * 1000L / Ft8.RATE; st.feed(b, b.length);
        }
    }

    @Test public void aSlotHeardOnlyDuringTheReceptionIsStillDecoded() {
        FakeHost h = new FakeHost(); Ft8Station st = new Ft8Station(h); st.start();
        float[] x = new float[Ft8.RATE * 15];
        float[] w = Ft8.synth(Ft8.tones(Ft8Message.pack("CQ K1ABC FN42", null)), 1200, Ft8.RATE);
        for (int i = 0; i < w.length; i++) x[Ft8.RATE / 2 + i] = 0.1f * w[i];
        long slot0 = h.now;
        feed(h, st, x, 0.25, 13.75);                                     // the capture opens with the signal and closes 0.7 s after it
        assertTrue("not before the slot is over", st.heard().isEmpty());
        for (h.now = slot0 + 13_750; h.now < slot0 + 15_000; h.now += 250) st.tick();
        assertEquals(1, st.heard().size());
        assertEquals("K1ABC", st.heard().get(0).from);
        for (; h.now < slot0 + 45_000; h.now += 250) st.tick();         // later slots without audio are not decoded again
        assertEquals(1, st.heard().size());
    }

    @Test public void answersACqAndCompletesTheQso() {
        FakeHost h = new FakeHost(); Ft8Station st = new Ft8Station(h); st.start();
        long slot = h.now / 15000;
        st.onDecoded(slot, java.util.Collections.singletonList(d("CQ K1ABC FN42", -7)));
        Ft8Station.Heard cq = st.heard().get(0);
        assertTrue(cq.cq); assertEquals("K1ABC", cq.from);
        assertNull(st.reply(cq));
        assertEquals((slot + 1) & 1, st.txParity());
        assertEquals("K1ABC W9XYZ DM79", nextTx(h, st));
        assertEquals((slot + 1) & 1, (h.txAt.get(0) / 15000 + 1) & 1);  // keyed just before a slot of the other period
        st.onDecoded(h.now / 15000, java.util.Collections.singletonList(d("W9XYZ K1ABC -12", -9)));
        assertEquals("K1ABC W9XYZ R-07", nextTx(h, st));                // the report we measured on the CQ
        st.onDecoded(h.now / 15000, java.util.Collections.singletonList(d("W9XYZ K1ABC RR73", -9)));
        assertEquals("[K1ABC FN42 -07 -12]", h.logged.toString());
        assertEquals("K1ABC W9XYZ 73", nextTx(h, st));
        assertFalse(st.txEnabled());
        assertNull(nextTx(h, st));
    }

    @Test public void cqAnsweredByACaller() {
        FakeHost h = new FakeHost(); Ft8Station st = new Ft8Station(h);
        assertNull(st.callCq());
        assertEquals("CQ W9XYZ DM79", nextTx(h, st));
        st.onDecoded(h.now / 15000, java.util.Collections.singletonList(d("W9XYZ G4ABC IO91", -15)));
        assertEquals("G4ABC", st.partner());
        assertEquals("G4ABC W9XYZ -15", nextTx(h, st));
        st.onDecoded(h.now / 15000, java.util.Collections.singletonList(d("W9XYZ G4ABC R-03", -14)));
        assertEquals("G4ABC W9XYZ RR73", nextTx(h, st));
        assertEquals("[G4ABC IO91 -15 -03]", h.logged.toString());
        st.onDecoded(h.now / 15000, java.util.Collections.singletonList(d("W9XYZ G4ABC 73", -14)));
        assertFalse(st.txEnabled());
    }

    @Test public void cqAnsweredByANonStandardCaller() {
        // a caller with a non-standard call cannot add a grid next to the hashed CQ call: its first reply is the two calls only
        FakeHost h = new FakeHost(); Ft8Station st = new Ft8Station(h);
        assertNull(st.callCq());
        assertEquals("CQ W9XYZ DM79", nextTx(h, st));
        Ft8.Decode raw = d("<W9XYZ> DMRTST", -12);                   // the station's own call resolves its hash: the text as it decodes on the air
        st.onDecoded(h.now / 15000, java.util.Collections.singletonList(new Ft8.Decode(raw.freqHz, raw.dtS, -12, raw.payload, "<W9XYZ> DMRTST")));
        assertEquals("DMRTST", st.partner());
        assertEquals("<...> W9XYZ -12", nextTx(h, st));              // the caller's call goes out hashed (this test's decoder has not heard it)
        st.onDecoded(h.now / 15000, java.util.Collections.singletonList(new Ft8.Decode(raw.freqHz, raw.dtS, -20, raw.payload, "<W9XYZ> DMRTST")));
        assertEquals("a repeated call keeps the report first sent", "<...> W9XYZ -12", nextTx(h, st));
        Ft8.Decode r = d("W9XYZ <DMRTST> R-05", -11);
        st.onDecoded(h.now / 15000, java.util.Collections.singletonList(new Ft8.Decode(r.freqHz, r.dtS, -11, r.payload, "W9XYZ <DMRTST> R-05")));
        assertEquals("<...> W9XYZ RR73", nextTx(h, st));
        assertEquals("[DMRTST null -12 -05]", h.logged.toString());
    }

    @Test public void aNonStandardCallAnswersACqAndCompletesTheQso() {
        FakeHost h = new FakeHost() { @Override public String callsign() { return "DMRTST"; } @Override public String grid() { return null; } };
        Ft8Station st = new Ft8Station(h); st.start();
        st.onDecoded(h.now / 15000, java.util.Collections.singletonList(d("CQ W9XYZ DM79", -9)));
        Ft8Station.Heard cq = st.heard().get(st.heard().size() - 1);
        assertNull(st.reply(cq));
        assertEquals("<...> DMRTST", nextTx(h, st));                  // type 4: our call in full, theirs hashed
        Ft8.Decode r = d("<DMRTST> W9XYZ -10", -8);
        st.onDecoded(h.now / 15000, java.util.Collections.singletonList(new Ft8.Decode(r.freqHz, r.dtS, -8, r.payload, "<DMRTST> W9XYZ -10")));
        assertEquals("W9XYZ <...> R-09", nextTx(h, st));             // our call hashed next to the report
        Ft8.Decode e = d("<DMRTST> W9XYZ RR73", -8);
        st.onDecoded(h.now / 15000, java.util.Collections.singletonList(new Ft8.Decode(e.freqHz, e.dtS, -8, e.payload, "<DMRTST> W9XYZ RR73")));
        assertEquals("W9XYZ <...> 73", nextTx(h, st));
        assertEquals("[W9XYZ DM79 -09 -10]", h.logged.toString());
    }

    @Test public void bracketedCallsPackAsHashes() {
        assertNotNull(Ft8Message.pack("W9XYZ <DMRTST> R-05", null));
        assertNotNull(Ft8Message.pack("<DMRTST> W9XYZ -12", null));
        assertNull("a bare non-standard sender with a report still does not fit", Ft8Message.pack("W9XYZ DMRTST R-05", null));
        assertTrue(Ft8Message.standardCall("W9XYZ")); assertTrue(Ft8Message.standardCall("Q0AAA")); assertFalse(Ft8Message.standardCall("DMRTST"));
    }

    @Test public void ignoresOthersDuringAQsoAndStopsWhenUnanswered() {
        FakeHost h = new FakeHost(); Ft8Station st = new Ft8Station(h);
        st.callCq();
        nextTx(h, st);
        st.onDecoded(h.now / 15000, java.util.Collections.singletonList(d("W9XYZ G4ABC IO91", -15)));
        st.onDecoded(h.now / 15000, java.util.Collections.singletonList(d("W9XYZ K1ABC FN42", -3)));   // a second caller: not now
        assertEquals("G4ABC", st.partner());
        int sent = 0; while (nextTx(h, st) != null && sent < 20) sent++;
        assertEquals(Ft8Station.WATCHDOG, sent);
        assertFalse(st.txEnabled());
    }

    @Test public void transmissionStartsHalfASecondIntoTheSlot() {
        FakeHost h = new FakeHost(); Ft8Station st = new Ft8Station(h);
        st.callCq(); nextTx(h, st);
        float[] x = new float[h.lastPcm.length]; for (int i = 0; i < x.length; i++) x[i] = h.lastPcm[i] / 32768f;
        Ft8.Decode got = Ft8.decode(h.padToSlot(x), Ft8.RATE * 15, new Ft8Message.Hashes()).get(0);
        assertEquals(0.0, got.dtS, 0.09);
        assertTrue("fits the radio's 30 s limit", h.lastPcm.length < Ft8.RATE * 28);
    }

    @Test public void slotAudioFromTheFeedDecodes() {
        FakeHost h = new FakeHost(); Ft8Station st = new Ft8Station(h); st.start();
        h.now += 15000;                                                  // a slot of audio arrives in 64 ms blocks
        long start = h.now;
        float[] w = Ft8.synth(Ft8.tones(Ft8Message.pack("CQ K1ABC FN42", null)), 900, Ft8.RATE);
        int total = Ft8.RATE * 15, block = 512;
        for (int i = 0; i < total; i += block) {
            byte[] b = new byte[block * 2];
            for (int j = 0; j < block; j++) { int k = i + j - Ft8.RATE / 2; short v = (short) (k >= 0 && k < w.length ? 3000 * w[k] : 0); b[2 * j] = (byte) v; b[2 * j + 1] = (byte) (v >> 8); }
            h.now = start + (i + block) * 1000L / Ft8.RATE;
            st.feed(b, b.length);
        }
        h.now = start + 14_800; st.feed(new byte[2], 2);
        assertEquals(1, st.heard().size());
        assertEquals("CQ K1ABC FN42", st.heard().get(0).d.text);
        assertEquals(0.0, st.heard().get(0).d.dtS, 0.09);
    }
}
