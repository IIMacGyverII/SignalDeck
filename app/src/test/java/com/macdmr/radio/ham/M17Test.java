/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.ham;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class M17Test {
    @Test public void specificationVectors() {
        assertEquals(0x772B, M17.crc16("123456789".getBytes(), 9));
        assertEquals(0x206E, M17.crc16("A".getBytes(), 1));
        assertEquals(0xFFFF, M17.crc16(new byte[0], 0));
        assertArrayEquals(new byte[] { 0x00, 0x00, 0x4B, 0x13, (byte) 0xD1, 0x06 }, M17.callsign("N0CALL"));
        assertEquals("N0CALL", M17.callsign(M17.callsign("N0CALL"), 0));
        assertEquals("@ALL", M17.callsign(M17.callsign("@ALL"), 0));
        assertEquals(0x0D7880F, M17.golay(0x0D78));
        assertEquals(137, M17.INTRL[1]);
    }

    @Test public void voiceSymbolsMatchTheResearchEncoder() {
        assertEquals("N0CALL", M17.voiceCall("n0call-7"));
        assertEquals("N0CALL", M17.voiceCall(""));
        assertArrayEquals(new byte[] { 0x4B }, M17.packSymbols(new int[] { 3, 1, -1, -3 }));
        int[] eot = M17.eotTail();
        assertEquals(192, eot.length);
        int[] pat = { 3, 3, 3, 3, 3, 3, -3, 3 };
        for (int i = 0; i < eot.length; i++) assertEquals("eot " + i, pat[i % 8], eot[i]);
        byte[] lsf = M17.lsf("N0CALL", "W9XYZ", 0x0005, null);
        int[] got = M17.lsfSymbols(lsf);
        int[] py = { 3,3,3,3,-3,-3,3,-3,-1,3,3,-1,1,-3,-3,3,-1,-1,-1,-1,-1,3,1,1 };
        for (int i = 0; i < py.length; i++) assertEquals("lsf " + i, py[i], got[i]);
        byte[] pay = new byte[16]; for (int i = 0; i < 16; i++) pay[i] = (byte) i;
        int[] st = M17.streamSymbols(lsf, 0x8000, pay, 5);
        int[] pySt = { -3,-3,-3,-3,3,3,-3,3,-3,-3,1,1,-1,-1,-1,3 };
        for (int i = 0; i < pySt.length; i++) assertEquals("str " + i, pySt[i], st[i]);
        assertEquals((byte) 0x55, M17.packSymbols(got)[0]);
        assertEquals((byte) 0xF7, M17.packSymbols(got)[1]);
    }

    @Test public void loopbackThroughTheCodes() {
        byte[] lsf = M17.lsf("W9XYZ", "@ALL", 0x0005, "HELLO M17".getBytes());
        M17.Lsf l = M17.decodeLsf(toFloat(M17.lsfSymbols(lsf)), 8);
        assertTrue(l.crcOk); assertEquals("W9XYZ", l.src()); assertEquals("@ALL", l.dst()); assertEquals("HELLO M17", l.metaText()); assertTrue(l.voice());
        Random r = new Random(1);
        for (int i = 0; i < 12; i++) {
            byte[] p = new byte[16]; r.nextBytes(p);
            float[] sym = toFloat(M17.streamSymbols(lsf, i, p, i % 6));
            for (int k = 8; k < 192; k++) sym[k] += (float) (0.4 * r.nextGaussian());   // noise
            M17.Stream s = M17.decodeStream(sym, 8);
            assertEquals(i, s.fn); assertArrayEquals(p, s.payload); assertEquals(i % 6, s.lichCount());
            assertArrayEquals(M17.streamSymbols(lsf, i, p, i % 6), M17.streamSymbolsFromLich(s.lich, s.fn, s.payload));
        }
    }

    static float[] toFloat(int[] a) { float[] f = new float[a.length]; for (int i = 0; i < a.length; i++) f[i] = a[i]; return f; }

    // ---- real radio audio: HackRF -> the owner's Armor 26 Ultra, four transmissions (seeds 1-4), 2026-10-22 ----

    static short[] wav(InputStream in) throws Exception {
        byte[] b = in.readAllBytes(); int data = 12;
        while (!(b[data] == 'd' && b[data + 1] == 'a' && b[data + 2] == 't' && b[data + 3] == 'a')) data += 8 + ((b[data + 4] & 0xFF) | (b[data + 5] & 0xFF) << 8);
        int n = ((b[data + 4] & 0xFF) | (b[data + 5] & 0xFF) << 8 | (b[data + 6] & 0xFF) << 16) / 2; short[] s = new short[n];
        for (int i = 0; i < n; i++) s[i] = (short) ((b[data + 8 + 2 * i] & 0xFF) | (b[data + 9 + 2 * i] << 8));
        return s;
    }
    static JSONObject meta() throws Exception { return new JSONObject(new String(M17Test.class.getResourceAsStream("/m17/m17_rx_armor26.json").readAllBytes(), "UTF-8")); }
    static int[] symbols(JSONObject part) throws Exception { String s = part.getString("symbols"); int[] o = new int[s.length()]; for (int i = 0; i < o.length; i++) o[i] = 2 * (s.charAt(i) - '0') - 3; return o; }

    @Test public void decodesRealRadioAudioAfterCalibration() throws Exception {
        short[] audio = wav(M17Test.class.getResourceAsStream("/m17/m17_rx_armor26.wav"));
        JSONObject m = meta(); JSONArray parts = m.getJSONArray("parts");
        JSONObject a = parts.getJSONObject(0);
        float[] cal = M17Receiver.calibrate(audio, a.getInt("start8k"), symbols(a));
        System.out.printf("calibration fit MSE %.3f%n", cal[M17Receiver.NCOEF]);
        assertTrue("calibration fits", cal[M17Receiver.NCOEF] < 0.1);
        String out = System.getenv("M17_CAL_OUT");
        if (out != null) { StringBuilder sb = new StringBuilder(); for (int i = 0; i < M17Receiver.NCOEF; i++) sb.append(cal[i]).append('\n'); java.nio.file.Files.write(java.nio.file.Paths.get(out), sb.toString().getBytes()); }

        final List<String> events = new ArrayList<>(); final List<byte[]> payloads = new ArrayList<>(); final List<Integer> fns = new ArrayList<>();
        M17Receiver rx = new M17Receiver(java.util.Arrays.copyOf(cal, M17Receiver.NCOEF), new M17Receiver.Listener() {
            public void started(M17.Lsf l) { events.add("start " + l.src() + " " + l.dst() + " " + l.crcOk); }
            public void frame(M17.Stream s, float q) { payloads.add(s.payload); fns.add(s.fn); }
            public void lsfFromLich(M17.Lsf l) { events.add("lich-lsf " + l.src()); }
            public void ended(int f, int g, String why) { events.add("end " + f + " " + g + " " + why); }
        });
        if (System.getenv("M17_ADAPT") != null) rx.adaptRate = Float.parseFloat(System.getenv("M17_ADAPT"));
        for (int i = 0; i < audio.length; i += 512) rx.feed(java.util.Arrays.copyOfRange(audio, i, Math.min(audio.length, i + 512)), Math.min(512, audio.length - i));
        System.out.println(events);
        // count exact payloads of the three transmissions it was not calibrated on
        int exact = 0;
        for (int pi = 1; pi < 4; pi++) {
            JSONArray pays = parts.getJSONObject(pi).getJSONArray("payloads");
            java.util.Set<String> want = new java.util.HashSet<>(); for (int k = 0; k < pays.length(); k++) want.add(pays.getString(k));
            for (byte[] p : payloads) { StringBuilder h = new StringBuilder(); for (byte x : p) h.append(String.format("%02x", x)); if (want.remove(h.toString())) exact++; }
        }
        System.out.println("exact stream payloads in the three test transmissions: " + exact + " of 300");
        long starts = events.stream().filter(e -> e.startsWith("start N0CALL ALL true")).count();
        assertEquals("four transmissions found, LSF decoded", 4, starts);
        assertTrue("exact " + exact, exact >= 290);
    }

    static JSONObject metaW6() throws Exception { return new JSONObject(new String(M17Test.class.getResourceAsStream("/m17/m17_rx_w6.json").readAllBytes(), "UTF-8")); }
    static int exactPayloads(List<byte[]> got, JSONObject part) throws Exception {
        JSONArray pays = part.getJSONArray("payloads");
        java.util.Set<String> want = new java.util.HashSet<>(); for (int k = 0; k < pays.length(); k++) want.add(pays.getString(k));
        int n = 0; for (byte[] p : got) { StringBuilder h = new StringBuilder(); for (byte x : p) h.append(String.format("%02x", x)); if (want.remove(h.toString())) n++; }
        return n;
    }

    /**
     * ANYSECU W6+ (SA818-class module, receive cut below ~300 Hz, steeper than the Armor's): a table fitted on one HackRF
     * transmission with the short feedback (M17Receiver.LEARN_FEEDBACK) decodes the other. Before the history corrections
     * (known sync, confirmed frames re-encoded, unconfirmed frames not fed back) the receiver lost the stream after 2-14 frames.
     * M17_W6_OUT writes the fitted table (the shipped assets/m17/eq_anysecu-w6plus.txt).
     */
    @Test public void decodesTheW6AfterCalibration() throws Exception {
        short[] audio = wav(M17Test.class.getResourceAsStream("/m17/m17_rx_w6.wav"));
        JSONArray parts = metaW6().getJSONArray("parts");
        float[] cal = M17Receiver.calibrate(audio, parts.getJSONObject(0).getInt("start8k"), symbols(parts.getJSONObject(0)), M17Receiver.LEARN_FEEDBACK);
        System.out.printf("W6 fit MSE %.3f%n", cal[M17Receiver.NCOEF]);
        String out = System.getenv("M17_W6_OUT");
        if (out != null) { StringBuilder sb = new StringBuilder(); for (int i = 0; i < M17Receiver.NCOEF; i++) sb.append(cal[i]).append('\n'); java.nio.file.Files.write(java.nio.file.Paths.get(out), sb.toString().getBytes("UTF-8")); }
        final List<byte[]> payloads = new ArrayList<>(); final List<String> events = new ArrayList<>();
        M17Receiver rx = new M17Receiver(java.util.Arrays.copyOf(cal, M17Receiver.NCOEF), new M17Receiver.Listener() {
            public void started(M17.Lsf l) { events.add("start " + l.src() + " " + l.crcOk); }
            public void frame(M17.Stream s, float q) { payloads.add(s.payload); }
            public void lsfFromLich(M17.Lsf l) { }
            public void ended(int f, int g, String why) { events.add("end " + f + " " + g + " " + why); }
        });
        rx.feed(audio, audio.length); rx.feed(new short[8000], 8000);
        int exact = exactPayloads(payloads, parts.getJSONObject(1));
        System.out.println(events + "\nW6: exact payloads in the other transmission: " + exact + " of 100");
        assertEquals(2, events.stream().filter(e -> e.equals("start N0CALL true")).count());
        assertTrue("exact " + exact, exact >= 85);
    }

    /**
     * ANYSECU W6Plus DMR (DMR006 module, camcorder input, audio inverted): a table fitted on one HackRF transmission with the
     * short feedback decodes the other. The residual is noisier than the W6+'s (fit MSE ~0.14; the receiver's timing holds,
     * the misses are scattered symbol errors), so about 80 of 100 frames come through exact (77-87 across feedback lengths
     * and fit direction on the bench). M17_W6DMR_OUT writes the fitted table (the shipped assets/m17/eq_anysecu-w6plus-dmr.txt).
     */
    @Test public void decodesTheW6DmrAfterCalibration() throws Exception {
        short[] audio = wav(M17Test.class.getResourceAsStream("/m17/m17_rx_w6dmr.wav"));
        JSONArray parts = new JSONObject(new String(M17Test.class.getResourceAsStream("/m17/m17_rx_w6dmr.json").readAllBytes(), "UTF-8")).getJSONArray("parts");
        float[] cal = M17Receiver.calibrate(audio, parts.getJSONObject(0).getInt("start8k"), symbols(parts.getJSONObject(0)), M17Receiver.LEARN_FEEDBACK);
        System.out.printf("W6 DMR fit MSE %.3f%n", cal[M17Receiver.NCOEF]);
        String out = System.getenv("M17_W6DMR_OUT");
        if (out != null) { StringBuilder sb = new StringBuilder(); for (int i = 0; i < M17Receiver.NCOEF; i++) sb.append(cal[i]).append('\n'); java.nio.file.Files.write(java.nio.file.Paths.get(out), sb.toString().getBytes("UTF-8")); }
        final List<byte[]> payloads = new ArrayList<>(); final List<String> events = new ArrayList<>();
        M17Receiver rx = new M17Receiver(java.util.Arrays.copyOf(cal, M17Receiver.NCOEF), new M17Receiver.Listener() {
            public void started(M17.Lsf l) { events.add("start " + l.src() + " " + l.crcOk); }
            public void frame(M17.Stream s, float q) { payloads.add(s.payload); }
            public void lsfFromLich(M17.Lsf l) { }
            public void ended(int f, int g, String why) { }
        });
        rx.feed(audio, audio.length); rx.feed(new short[8000], 8000);
        int exact = exactPayloads(payloads, parts.getJSONObject(1));
        System.out.println("W6 DMR: exact payloads in the other transmission: " + exact + " of 100");
        assertEquals(events.toString(), 2, events.stream().filter(e -> e.equals("start N0CALL true")).count());
        assertTrue("exact " + exact, exact >= 72);
    }

    /**
     * The same W6+ transmissions recorded later in a clip (2026-10-30): the 40 ms preamble then spans two of the detector's
     * 160-sample windows instead of three, the end estimate is a hop early and the sync lay outside the old +-60 symbol search.
     * With the shipped table both transmissions must decode, as the first recording does.
     */
    @Test public void decodesTheW6WhenThePreambleSpansTwoWindows() throws Exception {
        short[] audio = wav(M17Test.class.getResourceAsStream("/m17/m17_rx_w6_late.wav"));
        JSONArray parts = new JSONObject(new String(M17Test.class.getResourceAsStream("/m17/m17_rx_w6_late.json").readAllBytes(), "UTF-8")).getJSONArray("parts");
        java.util.List<String> lines = java.nio.file.Files.readAllLines(java.nio.file.Paths.get("src/main/assets/m17/eq_anysecu-w6plus.txt"));
        float[] cal = new float[M17Receiver.NCOEF]; for (int i = 0; i < cal.length; i++) cal[i] = Float.parseFloat(lines.get(i).trim());
        final List<byte[]> payloads = new ArrayList<>(); final List<String> events = new ArrayList<>();
        M17Receiver rx = new M17Receiver(cal, new M17Receiver.Listener() {
            public void started(M17.Lsf l) { events.add("start " + l.src() + " " + l.crcOk); }
            public void frame(M17.Stream s, float q) { payloads.add(s.payload); }
            public void lsfFromLich(M17.Lsf l) { }
            public void ended(int f, int g, String why) { }
        });
        rx.feed(audio, audio.length); rx.feed(new short[8000], 8000);
        assertEquals(events.toString(), 2, events.stream().filter(e -> e.equals("start N0CALL true")).count());
        int e1 = exactPayloads(new ArrayList<>(payloads), parts.getJSONObject(0)), e2 = exactPayloads(new ArrayList<>(payloads), parts.getJSONObject(1));
        assertTrue("exact " + e1 + " and " + e2, e1 >= 90 && e2 >= 90);
    }

    /**
     * Unihertz Atom XL (the bridge's USB card records about 125 ppm fast: 2.4 symbols over a 4 s transmission). The fit
     * finds the clock offset itself; a table fitted on one HackRF transmission decodes another one recorded five hours
     * earlier (89 of 100 exact on the bench; the receiver follows the drift frame by frame). M17_AXL_OUT writes the fitted
     * table (the shipped assets/m17/eq_unihertz-atomxl.txt).
     */
    @Test public void decodesTheAtomXlAfterAClockAwareFit() throws Exception {
        JSONObject fit = new JSONObject(new String(M17Test.class.getResourceAsStream("/m17/m17_rx_atomxl_s9.json").readAllBytes(), "UTF-8")).getJSONArray("parts").getJSONObject(0);
        JSONObject test = new JSONObject(new String(M17Test.class.getResourceAsStream("/m17/m17_rx_atomxl_s7.json").readAllBytes(), "UTF-8")).getJSONArray("parts").getJSONObject(0);
        short[] fa = wav(M17Test.class.getResourceAsStream("/m17/m17_rx_atomxl_s9.wav")), ta = wav(M17Test.class.getResourceAsStream("/m17/m17_rx_atomxl_s7.wav"));
        float[] cal = M17Receiver.calibrate(fa, fit.getInt("start8k"), symbols(fit), M17Receiver.LEARN_FEEDBACK);
        System.out.printf("Atom XL fit MSE %.3f, clock %+.0f ppm%n", cal[M17Receiver.NCOEF], cal[M17Receiver.NCOEF + 1]);
        assertTrue("the clock offset is found", cal[M17Receiver.NCOEF + 1] > 90 && cal[M17Receiver.NCOEF + 1] < 160);
        assertTrue("fits once the clock is followed", cal[M17Receiver.NCOEF] < 0.3);
        String out = System.getenv("M17_AXL_OUT");
        if (out != null) { StringBuilder sb = new StringBuilder(); for (int i = 0; i < M17Receiver.NCOEF; i++) sb.append(cal[i]).append('\n'); java.nio.file.Files.write(java.nio.file.Paths.get(out), sb.toString().getBytes("UTF-8")); }
        final List<byte[]> payloads = new ArrayList<>();
        M17Receiver rx = new M17Receiver(java.util.Arrays.copyOf(cal, M17Receiver.NCOEF), new M17Receiver.Listener() {
            public void started(M17.Lsf l) { } public void frame(M17.Stream s, float q) { payloads.add(s.payload); }
            public void lsfFromLich(M17.Lsf l) { } public void ended(int f, int g, String why) { }
        });
        rx.feed(ta, ta.length); rx.feed(new short[8000], 8000);
        int exact = exactPayloads(payloads, test);
        System.out.println("Atom XL: exact payloads in the transmission not fitted: " + exact + " of 100");
        assertTrue("exact " + exact, exact >= 80);
    }

    /** Self-calibration on the W6: the table learned from one call's confirmed frames alone decodes the other call. */
    @Test public void learnsATableFromConfirmedFrames() throws Exception {
        short[] audio = wav(M17Test.class.getResourceAsStream("/m17/m17_rx_w6.wav"));
        JSONArray parts = metaW6().getJSONArray("parts");
        float[] seed = java.util.Arrays.copyOf(M17Receiver.calibrate(audio, parts.getJSONObject(0).getInt("start8k"), symbols(parts.getJSONObject(0)), M17Receiver.LEARN_FEEDBACK), M17Receiver.NCOEF);
        final M17Receiver[] holder = new M17Receiver[1]; final List<float[]> learned = new ArrayList<>(); final List<Integer> counts = new ArrayList<>();
        holder[0] = new M17Receiver(seed, new M17Receiver.Listener() {
            public void started(M17.Lsf l) { }
            public void frame(M17.Stream s, float q) { }
            public void lsfFromLich(M17.Lsf l) { }
            public void ended(int f, int g, String why) { counts.add(holder[0].learnedFrames()); learned.add(holder[0].learnedTable(20, M17Receiver.LEARN_FEEDBACK)); }
        });
        holder[0].feed(audio, audio.length); holder[0].feed(new short[8000], 8000);
        System.out.println("confirmed frames learned per call: " + counts);
        assertEquals(2, learned.size());
        assertNotNull(learned.get(1));
        short[] first = java.util.Arrays.copyOfRange(audio, 0, parts.getJSONObject(1).getInt("start8k") - 4000);
        int[] withLearned = M17Receiver.score(first, learned.get(1)), withSeed = M17Receiver.score(first, seed);
        System.out.println("first call: learned table " + java.util.Arrays.toString(withLearned) + ", seed " + java.util.Arrays.toString(withSeed));
        assertEquals(1, withLearned[2]);
        assertTrue("learned " + withLearned[0], withLearned[0] >= 85);
        assertNull("fewer than 20 confirmed frames: no table", new M17Receiver(seed, null).learnedTable(20, 4));
    }
}
