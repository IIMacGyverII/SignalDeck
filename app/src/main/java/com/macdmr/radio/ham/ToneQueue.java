/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.ham;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The module's tone queue (bench firmware FW21 and later, command {@code 0x40} ops 10 and 11): tones played by the
 * HR-C7000 modulator from TX RAM instead of through the phone's audio path, so Morse, RTTY and FT8 go out without
 * the audio path's low cut, pre-emphasis and limiter. A pair is a frequency in eighths of a hertz (0 to 8191 Hz;
 * FT8's 6.25 Hz is 50) and a duration in milliseconds (1 to 10000); a 0 Hz pair is silence with the phase held.
 * Every body starts with an int16 amplitude in TX-RAM counts (about 0.41 Hz of deviation per count), then up to
 * {@link #PAIRS_PER_BODY} pairs; the module holds 64. Op 11 arms the end: the module drops the carrier when the
 * queue drains (FW20's clean end).
 */
public final class ToneQueue {
    private ToneQueue() { }

    public static final int OP_MORE = 10, OP_LAST = 11;
    /** Pairs in one body: the module holds 64, and the host tops up when the queue is below {@link #TOP_UP_AT}. */
    public static final int PAIRS_PER_BODY = 30, TOP_UP_AT = 24;
    /** The amplitude the bench used for Morse, RTTY and FT8 (p99 deviation about 2 kHz). */
    public static final int AMPLITUDE = 4000;
    /** Silence before the first tone: the TX RAM half filled at key-up is played before the queue starts. */
    public static final int LEAD_MS = 150;
    /** The first bench image with the tone queue; FW22 arms the TX-RAM path for a tone-only key. */
    public static final int FIRST_IMAGE = 22;

    /** One queued tone: frequency in eighths of a hertz and duration in milliseconds. */
    public static final class Pair {
        public final int eighths, ms;
        public Pair(int eighths, int ms) {
            if (eighths < 0 || eighths > 65535) throw new IllegalArgumentException("frequency");
            if (ms < 1 || ms > 10000) throw new IllegalArgumentException("duration");
            this.eighths = eighths; this.ms = ms;
        }
        public static Pair hz(double hz, int ms) { return new Pair((int) Math.round(hz * 8), ms); }
        public static Pair silence(int ms) { return new Pair(0, ms); }
        public double hz() { return eighths / 8.0; }
        @Override public String toString() { return String.format(Locale.US, "%.3f Hz %d ms", hz(), ms); }
    }

    /** Total length of a pair list in milliseconds. */
    public static int durationMs(List<Pair> pairs) { int t = 0; for (Pair p : pairs) t += p.ms; return t; }

    /** The op 10 or op 11 body: opcode, amplitude (int16 LE, clamped to ±10000), then the pairs (uint16 LE eighths, uint16 LE ms). */
    public static byte[] body(boolean last, int amplitude, List<Pair> pairs) {
        if (pairs.isEmpty() || pairs.size() > 64) throw new IllegalArgumentException("pairs");
        int a = Math.max(-10000, Math.min(10000, amplitude));
        byte[] b = new byte[3 + 4 * pairs.size()];
        b[0] = (byte) (last ? OP_LAST : OP_MORE);
        b[1] = (byte) a; b[2] = (byte) (a >> 8);
        int k = 3;
        for (Pair p : pairs) { b[k++] = (byte) p.eighths; b[k++] = (byte) (p.eighths >> 8); b[k++] = (byte) p.ms; b[k++] = (byte) (p.ms >> 8); }
        return b;
    }

    /** The pairs cut into bodies of at most {@link #PAIRS_PER_BODY}; the last one is op 11. */
    public static List<byte[]> bodies(int amplitude, List<Pair> pairs) {
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < pairs.size(); i += PAIRS_PER_BODY) {
            int end = Math.min(pairs.size(), i + PAIRS_PER_BODY);
            out.add(body(end == pairs.size(), amplitude, pairs.subList(i, end)));
        }
        return out;
    }

    /** Merges neighbouring pairs of the same frequency (a run of marks, a word gap) so the queue carries fewer entries. */
    static List<Pair> merged(List<Pair> in) {
        List<Pair> out = new ArrayList<>();
        for (Pair p : in) {
            Pair last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && last.eighths == p.eighths && last.ms + p.ms <= 10000) out.set(out.size() - 1, new Pair(p.eighths, last.ms + p.ms));
            else out.add(p);
        }
        return out;
    }

    // ---- Morse ----

    /** Morse at {@code wpm} (dit = 1200 / wpm ms) on {@code hz}, after {@link #LEAD_MS} of silence, ending in one dit of silence. */
    public static List<Pair> morse(String text, int wpm, double hz) {
        int dit = Math.max(20, 1200 / Math.max(5, wpm));
        List<Pair> out = new ArrayList<>();
        out.add(Pair.silence(LEAD_MS));
        boolean first = true;
        for (String word : text.trim().toUpperCase(Locale.US).split("\\s+")) {
            if (word.isEmpty()) continue;
            if (!first) out.add(Pair.silence(7 * dit)); first = false;
            boolean firstLetter = true;
            for (char ch : word.toCharArray()) {
                String c = com.macdmr.radio.coverage.Morse.code(ch); if (c == null) continue;
                if (!firstLetter) out.add(Pair.silence(3 * dit)); firstLetter = false;
                for (int k = 0; k < c.length(); k++) {
                    if (k > 0) out.add(Pair.silence(dit));
                    out.add(Pair.hz(hz, (c.charAt(k) == '-' ? 3 : 1) * dit));
                }
            }
        }
        out.add(Pair.silence(dit));
        return merged(out);
    }

    // ---- RTTY (45.45 baud, 170 Hz shift, ITA2, 1.5 stop bits) ----

    private static final String LTRS = "\0E\nA SIU\rDRJNFCKTZLWHYPQOBG\u000EMXV\u000F";   // i18n: Baudot code table
    private static final String FIGS = "\u0000" + "3\n- \u000787\r$4',!:(5\")2#6019?&\u000E./;\u000F";
    private static final int LTRS_CODE = 31, FIGS_CODE = 27;
    /** One bit at 45.45 baud is 22.0 ms; the stop element 33 ms. The queue takes whole milliseconds, so the character is 22 × 6 + 33 = 165 ms (165.0 at the nominal rate). */
    public static final int RTTY_BIT_MS = 22, RTTY_STOP_MS = 33;

    /** RTTY of {@code text}, mark {@code markHz}, space 170 Hz above, after {@code idleMs} of mark (the receiver's lock), LTRS first and last. */
    public static List<Pair> rtty(String text, double markHz, int idleMs) {
        List<Pair> out = new ArrayList<>();
        out.add(Pair.silence(LEAD_MS));
        int mark = (int) Math.round(markHz * 8), space = (int) Math.round((markHz + Rtty.SHIFT) * 8);
        out.add(new Pair(mark, Math.max(1, Math.min(10000, idleMs))));
        boolean figs = false;
        rttyChar(out, LTRS_CODE, mark, space);
        for (char ch : text.toUpperCase(Locale.US).toCharArray()) {
            if (ch == '\n') { rttyChar(out, 8, mark, space); rttyChar(out, 2, mark, space); continue; }
            int l = LTRS.indexOf(ch), f = FIGS.indexOf(ch);
            if (ch == ' ') { rttyChar(out, 4, mark, space); figs = false; continue; }
            if (l > 0 && l != LTRS_CODE && l != FIGS_CODE) { if (figs) { rttyChar(out, LTRS_CODE, mark, space); figs = false; } rttyChar(out, l, mark, space); }
            else if (f > 0 && f != LTRS_CODE && f != FIGS_CODE) { if (!figs) { rttyChar(out, FIGS_CODE, mark, space); figs = true; } rttyChar(out, f, mark, space); }
        }
        rttyChar(out, LTRS_CODE, mark, space);
        out.add(new Pair(mark, 300));
        return merged(out);
    }
    private static void rttyChar(List<Pair> out, int code, int mark, int space) {
        out.add(new Pair(space, RTTY_BIT_MS));                                 // start
        for (int b = 0; b < 5; b++) out.add(new Pair(((code >> b) & 1) == 1 ? mark : space, RTTY_BIT_MS));
        out.add(new Pair(mark, RTTY_STOP_MS));
    }

    // ---- FT8 (79 tones of 160 ms, 6.25 Hz apart) ----

    public static final int FT8_TONE_MS = 160;
    /** FT8's 79 tones above {@code f0}, after {@code leadMs} of silence (the slot timing), tone k at f0 + 6.25 k Hz. */
    public static List<Pair> ft8(int[] tones, double f0, int leadMs) {
        List<Pair> out = new ArrayList<>();
        int lead = Math.max(LEAD_MS, leadMs);
        while (lead > 0) { int n = Math.min(10000, lead); out.add(Pair.silence(n)); lead -= n; }
        int base = (int) Math.round(f0 * 8);
        for (int t : tones) out.add(new Pair(base + 50 * t, FT8_TONE_MS));
        return merged(out);
    }
}
