/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.pkt;

import android.graphics.Bitmap;

/**
 * SSTV encoder. The short Robot modes (24 Color, 12 BW, 8 BW) fit any transmit limit; the long modes (Robot 36,
 * Martin M1 and M2, Scottie S1 and S2, PD 90) need the radio's own 120 s time-out, which the module has once the
 * app stops asking for none (the 30 s limit was the app's own countdown, measured 2026-10-25).
 *
 * Output is 8 kHz 16-bit mono PCM (the OEM mic path rate); the caller stereo-duplicates it into the
 * TX injection buffer. VIS uses the standard polarity (logical 1 = 1100 Hz, 0 = 1300 Hz), Robot
 * luma/chroma are full-range (Y 0..255 ↔ 1500..2300 Hz, Cb/Cr centred on 128) as the module's own
 * decoder, pySSTV and MMSSTV expect; Martin and Scottie send G, B, R at 1500..2300 Hz; PD sends the Y of two
 * lines around one shared R-Y / B-Y pair. Tones are continuous-phase with fractional-sample timing so
 * line timing does not drift across the image.
 */
public final class SSTVEncoder {
    public static final int SAMPLE_RATE = 8000;
    private static final double F_SYNC = 1200, F_BLACK = 1500, F_WHITE = 2300, F_LEADER = 1900;
    private static final double F_BIT1 = 1100, F_BIT0 = 1300;
    private static final double AMP = 30000;

    private SSTVEncoder() { }

    /** Supported modes (VIS codes). */
    public static final int ROBOT_24_COLOR = SSTVMode.MODE_ROBOT_24_C;   // 0x04, 320x120 lines (RX doubles to 240), ~24 s
    public static final int ROBOT_12_BW    = SSTVMode.MODE_ROBOT_12_BW;  // 0x03, 160x120, ~12 s
    public static final int ROBOT_8_BW     = SSTVMode.MODE_ROBOT_8_BW;   // 0x02, 160x120, ~8 s
    public static final int ROBOT_36       = SSTVMode.MODE_ROBOT_36;     // 0x08, 320x240, 36 s
    public static final int MARTIN_M1      = SSTVMode.MODE_MARTIN_M1;    // 0x2C, 320x256, 114 s
    public static final int MARTIN_M2      = SSTVMode.MODE_MARTIN_M2;    // 0x28, 320x256, 58 s
    public static final int SCOTTIE_S1     = SSTVMode.MODE_SCOTTIE_S1;   // 0x3C, 320x256, 110 s
    public static final int SCOTTIE_S2     = SSTVMode.MODE_SCOTTIE_S2;   // 0x38, 320x256, 71 s
    public static final int PD_90          = SSTVMode.MODE_PD_90;        // 0x63, 320x256, 90 s

    /** Every mode the encoder can send, shortest first: the pickers list them in this order. */
    public static final int[] MODES = { ROBOT_8_BW, ROBOT_12_BW, ROBOT_24_COLOR, ROBOT_36, MARTIN_M2, SCOTTIE_S2, PD_90, SCOTTIE_S1, MARTIN_M1 };

    public static boolean isSupported(int mode) { for (int m : MODES) if (m == mode) return true; return false; }

    /** True for the modes that run past 30 s and so need the module's own time-out and the cool-down guard. */
    public static boolean isLong(int mode) { return durationMs(mode) > 30_000; }

    public static String name(int mode) {
        switch (mode) {
            case ROBOT_24_COLOR: return "Robot 24 Color";   // i18n: mode name
            case ROBOT_12_BW: return "Robot 12 BW";   // i18n: mode name
            case ROBOT_8_BW: return "Robot 8 BW";   // i18n: mode name
            case ROBOT_36: return "Robot 36";   // i18n: mode name
            case MARTIN_M1: return "Martin M1";   // i18n: mode name
            case MARTIN_M2: return "Martin M2";   // i18n: mode name
            case SCOTTIE_S1: return "Scottie S1";   // i18n: mode name
            case SCOTTIE_S2: return "Scottie S2";   // i18n: mode name
            case PD_90: return "PD 90";   // i18n: mode name
            default: return "?";
        }
    }

    /** The picker's label: name and rounded length. */
    public static String label(int mode) { return name(mode) + " (≈" + Math.round(durationMs(mode) / 1000.0) + " s)"; }

    // ---- timings (milliseconds), from the mode specifications ----
    private static final double M1_SCAN = 146.432, M2_SCAN = 73.216, MARTIN_SYNC = 4.862, MARTIN_GAP = 0.572;
    private static final double S1_SCAN = 138.240, S2_SCAN = 88.064, SCOTTIE_SYNC = 9.0, SCOTTIE_GAP = 1.5;
    private static final double PD90_SCAN = 170.240, PD_SYNC = 20.0, PD_PORCH = 2.08;

    static int lines(int mode) { return mode == ROBOT_24_COLOR || mode == ROBOT_12_BW || mode == ROBOT_8_BW ? 120 : mode == ROBOT_36 ? 240 : 256; }
    static int width(int mode) { return mode == ROBOT_12_BW || mode == ROBOT_8_BW ? 160 : 320; }

    /** One scan line (sync to sync) in ms; for PD 90 the pair of lines one sync carries. */
    static double lineMs(int mode) {
        switch (mode) {
            case ROBOT_24_COLOR: return 6 + 3 + 88 + 4.5 + 1.5 + 44 + 4.5 + 1.5 + 44;
            case ROBOT_12_BW: return 7 + 3 + 90;
            case ROBOT_8_BW: return 7 + 3 + 56;
            case ROBOT_36: return 9 + 3 + 88 + 4.5 + 1.5 + 44;
            case MARTIN_M1: return MARTIN_SYNC + 4 * MARTIN_GAP + 3 * M1_SCAN;
            case MARTIN_M2: return MARTIN_SYNC + 4 * MARTIN_GAP + 3 * M2_SCAN;
            case SCOTTIE_S1: return SCOTTIE_SYNC + 3 * SCOTTIE_GAP + 3 * S1_SCAN;
            case SCOTTIE_S2: return SCOTTIE_SYNC + 3 * SCOTTIE_GAP + 3 * S2_SCAN;
            case PD_90: return PD_SYNC + PD_PORCH + 4 * PD90_SCAN;
            default: return 0;
        }
    }

    /** Audio duration in ms (leader, VIS, image, tail), for the transmit limit and the pickers. */
    public static int durationMs(int mode) {
        int n = mode == PD_90 ? lines(mode) / 2 : lines(mode);
        return (int) (100 + 300 + 10 + 300 + 30 + 9 * 30 + n * lineMs(mode) + 100);
    }

    /** Encode a bitmap (any size; it is scaled to the mode's geometry). Returns 8 kHz mono PCM. */
    public static short[] encode(Bitmap src, int mode) {
        int w = width(mode), h = lines(mode);
        Bitmap bmp = Bitmap.createScaledBitmap(src, w, h, true);
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        return encodePixels(px, w, h, mode);
    }

    /** The encoder proper, on pixels already at the mode's geometry (ARGB ints, row-major). Host tests use this. */
    static short[] encodePixels(int[] px, int w, int h, int mode) {
        Tone t = new Tone(durationMs(mode));
        t.tone(F_LEADER, 100);          // a little extra leader so the first 1900 Hz is clean after key-up
        vis(t, mode);

        double[] y = new double[w], cr = new double[w], cb = new double[w], r = new double[w], g = new double[w], b = new double[w];
        double[] y2 = new double[w], cr2 = new double[w], cb2 = new double[w];
        if (mode == SCOTTIE_S1 || mode == SCOTTIE_S2) t.tone(F_SYNC, SCOTTIE_SYNC);   // Scottie: one sync before the first line, then the sync sits before R
        for (int line = 0; line < h; line++) {
            rowOf(px, w, line, y, cb, cr, r, g, b);
            switch (mode) {
                case ROBOT_24_COLOR:
                    t.tone(F_SYNC, 6); t.tone(F_BLACK, 3);
                    t.pixels(y, 88);
                    t.tone(1500, 4.5); t.tone(1900, 1.5); t.pixels(cr, 44);
                    t.tone(2300, 4.5); t.tone(1900, 1.5); t.pixels(cb, 44);
                    break;
                case ROBOT_36:                                         // Y every line; R-Y on even lines, B-Y on odd (one chroma pair over two lines)
                    t.tone(F_SYNC, 9); t.tone(F_BLACK, 3);
                    t.pixels(y, 88);
                    if ((line & 1) == 0) { t.tone(1500, 4.5); t.tone(1900, 1.5); t.pixels(cr, 44); }
                    else { t.tone(2300, 4.5); t.tone(1900, 1.5); t.pixels(cb, 44); }
                    break;
                case MARTIN_M1: case MARTIN_M2: {
                    double scan = mode == MARTIN_M1 ? M1_SCAN : M2_SCAN;
                    t.tone(F_SYNC, MARTIN_SYNC); t.tone(F_BLACK, MARTIN_GAP);
                    t.pixels(g, scan); t.tone(F_BLACK, MARTIN_GAP);
                    t.pixels(b, scan); t.tone(F_BLACK, MARTIN_GAP);
                    t.pixels(r, scan); t.tone(F_BLACK, MARTIN_GAP);
                    break;
                }
                case SCOTTIE_S1: case SCOTTIE_S2: {
                    double scan = mode == SCOTTIE_S1 ? S1_SCAN : S2_SCAN;
                    t.tone(F_BLACK, SCOTTIE_GAP); t.pixels(g, scan);
                    t.tone(F_BLACK, SCOTTIE_GAP); t.pixels(b, scan);
                    t.tone(F_SYNC, SCOTTIE_SYNC); t.tone(F_BLACK, SCOTTIE_GAP); t.pixels(r, scan);
                    break;
                }
                case PD_90: {                                          // two lines per sync: Y(odd), R-Y, B-Y (averaged), Y(even)
                    if (line + 1 >= h) break;
                    rowOf(px, w, line + 1, y2, cb2, cr2, r, g, b);
                    for (int x = 0; x < w; x++) { cr[x] = 0.5 * (cr[x] + cr2[x]); cb[x] = 0.5 * (cb[x] + cb2[x]); }
                    t.tone(F_SYNC, PD_SYNC); t.tone(F_BLACK, PD_PORCH);
                    t.pixels(y, PD90_SCAN); t.pixels(cr, PD90_SCAN); t.pixels(cb, PD90_SCAN); t.pixels(y2, PD90_SCAN);
                    line++;
                    break;
                }
                default:
                    t.tone(F_SYNC, 7); t.tone(F_BLACK, 3);
                    t.pixels(y, mode == ROBOT_12_BW ? 90 : 56);
            }
        }
        t.tone(F_LEADER, 100);
        return t.toShorts();
    }

    private static void rowOf(int[] px, int w, int line, double[] y, double[] cb, double[] cr, double[] r, double[] g, double[] b) {
        for (int x = 0; x < w; x++) {
            int p = px[line * w + x];
            double rr = (p >> 16) & 0xFF, gg = (p >> 8) & 0xFF, bb = p & 0xFF;
            r[x] = rr; g[x] = gg; b[x] = bb;
            y[x]  = clamp(0.299 * rr + 0.587 * gg + 0.114 * bb);
            cb[x] = clamp(128 - 0.168736 * rr - 0.331264 * gg + 0.5 * bb);
            cr[x] = clamp(128 + 0.5 * rr - 0.418688 * gg - 0.081312 * bb);
        }
    }

    private static double clamp(double v) { return v < 0 ? 0 : v > 255 ? 255 : v; }

    private static void vis(Tone t, int code) {
        t.tone(F_LEADER, 300); t.tone(F_SYNC, 10); t.tone(F_LEADER, 300);
        t.tone(F_SYNC, 30);                              // start bit
        int ones = 0;
        for (int i = 0; i < 7; i++) {
            int bit = (code >> i) & 1; ones += bit;
            t.tone(bit == 1 ? F_BIT1 : F_BIT0, 30);      // LSB first
        }
        t.tone((ones % 2) == 1 ? F_BIT1 : F_BIT0, 30);   // even parity
        t.tone(F_SYNC, 30);                              // stop bit
    }

    /** Continuous-phase tone generator with fractional-sample timing carry. */
    private static final class Tone {
        private double phase = 0, tErr = 0;
        private short[] buf;
        private int n = 0;

        Tone(int expectMs) { buf = new short[SAMPLE_RATE * (expectMs / 1000 + 2)]; }

        private void ensure(int more) {
            if (n + more > buf.length) {
                short[] nb = new short[Math.max(buf.length * 2, n + more)];
                System.arraycopy(buf, 0, nb, 0, n); buf = nb;
            }
        }
        private int samplesFor(double ms) {
            double nf = ms * SAMPLE_RATE / 1000.0 + tErr;
            int k = (int) Math.round(nf); tErr = nf - k; return Math.max(k, 0);
        }
        void tone(double freq, double ms) {
            int k = samplesFor(ms); ensure(k);
            double dph = 2 * Math.PI * freq / SAMPLE_RATE;
            for (int i = 0; i < k; i++) { phase += dph; buf[n++] = (short) (AMP * Math.sin(phase)); }
            phase %= (2 * Math.PI);
        }
        void pixels(double[] vals, double ms) {
            int k = samplesFor(ms); ensure(k); int w = vals.length;
            for (int i = 0; i < k; i++) {
                double v = vals[(int) ((long) i * w / k)];
                double f = F_BLACK + (F_WHITE - F_BLACK) * v / 255.0;
                phase += 2 * Math.PI * f / SAMPLE_RATE;
                buf[n++] = (short) (AMP * Math.sin(phase));
            }
            phase %= (2 * Math.PI);
        }
        short[] toShorts() { short[] out = new short[n]; System.arraycopy(buf, 0, out, 0, n); return out; }
    }
}
