/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.ham;

import com.macdmr.radio.i18n.Txt;
import com.macdmr.radio.R;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The spin: the hunter holds the phone against the chest and turns slowly through a full circle. The body shadows the
 * signal from behind, so the strength against the compass heading has a broad peak toward the fox and a sharper null
 * away from it. This collects (heading, dBm) pairs, guides the turn (slower / keep going / how much of the circle is
 * covered), and fits the curve: a circular Gaussian smoothing (a weighted mean, so an uneven turn does not bias it),
 * the bearing at the peak or, when the null is the deeper feature, opposite the null; the quality (one sigma, degrees)
 * comes from the peak-to-null depth against the sample spread and the width of the feature used. A sweep with too
 * little depth, too few readings or a gap asks for a retry instead of storing a poor bearing. Pure Java.
 */
public final class SpinBearing {
    public static final int STEPS = 72;                           // the fitted curve, every 5 degrees
    public static final double SMOOTH_DEG = 20, MIN_DEPTH_DB = 6, MIN_COVER = 0.92, MAX_GAP_DEG = 45, FAST_DPS = 45, SLOW_DPS = 4;
    public static final int MIN_SAMPLES = 16, SECTORS = 36;

    /** The fit: ok with a bearing, or a retry reason. */
    public static final class Result {
        public boolean ok; public String retry;
        public double bearing = Double.NaN, sigma = Double.NaN, peakDb, nullDb, depth, noise, peakAt, nullAt;
        public boolean fromNull; public int samples;
        public double[] curve;                                    // dBm every 360/STEPS degrees from north, null when too few readings
        public String describe() {
            if (!ok) return retry;
            return Txt.s(fromNull ? R.string.modes_spin_result_null : R.string.modes_spin_result_peak, String.format(java.util.Locale.US, "%03.0f", bearing), String.format(java.util.Locale.US, "%.0f", sigma), String.format(java.util.Locale.US, "%.0f", depth));
        }
    }

    private final List<double[]> samples = new ArrayList<>();      // {t, heading, dBm}
    private final List<double[]> track = new ArrayList<>();        // {t, unwrapped heading}
    private double unwrapped = Double.NaN, start = Double.NaN, last = Double.NaN;

    /** The compass heading now (every tick, readings or not): the turn's progress and speed. */
    public void heading(long t, double deg) {
        if (Double.isNaN(deg)) return;
        if (Double.isNaN(last)) { unwrapped = start = deg; }
        else unwrapped += FoxSignalGate.diff(deg, last);
        last = deg;
        track.add(new double[] { t, unwrapped });
        while (track.size() > 2 && track.get(0)[0] < t - 2000) track.remove(0);
    }

    /** A signal reading taken while the phone pointed at {@code deg}. */
    public void add(long t, double deg, int dbm) { if (!Double.isNaN(deg) && dbm != -999) samples.add(new double[] { t, (deg % 360 + 360) % 360, dbm }); }

    public int samples() { return samples.size(); }
    /** Degrees turned so far (net, either way). */
    public double turned() { return Double.isNaN(unwrapped) ? 0 : Math.abs(unwrapped - start); }
    /** Turn speed (degrees a second) over the last two seconds. */
    public double rate() {
        if (track.size() < 2) return 0;
        double[] a = track.get(0), b = track.get(track.size() - 1);
        return b[0] > a[0] ? Math.abs(b[1] - a[1]) * 1000 / (b[0] - a[0]) : 0;
    }
    /** Which of the 36 ten-degree sectors hold a reading. */
    public boolean[] sectors() { boolean[] s = new boolean[SECTORS]; for (double[] x : samples) s[(int) (x[1] / (360.0 / SECTORS)) % SECTORS] = true; return s; }
    /** The mean reading in each of the 36 sectors (dBm, NaN = none yet), for the ring on screen. */
    public double[] sectorMeans() {
        double[] sum = new double[SECTORS]; int[] n = new int[SECTORS];
        for (double[] x : samples) { int k = (int) (x[1] / (360.0 / SECTORS)) % SECTORS; sum[k] += x[2]; n[k]++; }
        for (int k = 0; k < SECTORS; k++) sum[k] = n[k] == 0 ? Double.NaN : sum[k] / n[k];
        return sum;
    }
    /** The last compass heading (NaN before the first). */
    public double lastHeading() { return last; }
    /** The share of the circle covered by readings, 0..1. */
    public double covered() { int n = 0; for (boolean b : sectors()) if (b) n++; return n / (double) SECTORS; }
    /** Full circle with readings all round. */
    public boolean complete() { return covered() >= MIN_COVER && turned() >= 340 && samples.size() >= MIN_SAMPLES; }

    /** What to tell the hunter now. */
    public String guide() {
        double r = rate();
        if (complete()) return Txt.s(R.string.modes_spin_done);
        if (r > FAST_DPS) return Txt.s(R.string.modes_spin_slower);
        if (track.size() >= 2 && r < SLOW_DPS) return Txt.s(R.string.modes_spin_keep_turning);
        return Txt.s(R.string.modes_spin_keep_going);
    }
    /** Whether {@link #guide} says to slow down (the screen colours that one amber), told by the state, not the words. */
    public boolean tooFast() { return !complete() && rate() > FAST_DPS; }

    public Result fit() { return fit(samples); }

    /** Fit a sweep given as {t, heading, dBm} rows. */
    public static Result fit(List<double[]> rows) {
        Result r = new Result(); r.samples = rows.size();
        if (rows.size() < MIN_SAMPLES) { r.retry = Txt.s(R.string.modes_spin_few); return r; }
        double[] hs = new double[rows.size()]; for (int i = 0; i < hs.length; i++) hs[i] = rows.get(i)[1];
        Arrays.sort(hs);
        double gap = 360 - hs[hs.length - 1] + hs[0]; for (int i = 1; i < hs.length; i++) gap = Math.max(gap, hs[i] - hs[i - 1]);
        if (gap > MAX_GAP_DEG) { r.retry = Txt.s(R.string.modes_spin_gap, String.format(java.util.Locale.US, "%.0f", gap)); return r; }
        double[] cv = new double[STEPS]; double k = 1 / (2 * SMOOTH_DEG * SMOOTH_DEG);
        for (int i = 0; i < STEPS; i++) {
            double a = i * 360.0 / STEPS, sw = 0, s = 0;
            for (double[] x : rows) { double dd = FoxSignalGate.diff(x[1], a), w = Math.exp(-dd * dd * k); sw += w; s += w * x[2]; }
            cv[i] = sw > 0 ? s / sw : Double.NaN;
        }
        r.curve = cv;
        double ss = 0; for (double[] x : rows) { double e = x[2] - at(cv, x[1]); ss += e * e; }
        r.noise = Math.sqrt(ss / rows.size());
        int pi = 0, ni = 0; for (int i = 1; i < STEPS; i++) { if (cv[i] > cv[pi]) pi = i; if (cv[i] < cv[ni]) ni = i; }
        r.peakDb = cv[pi]; r.nullDb = cv[ni]; r.depth = r.peakDb - r.nullDb;
        r.peakAt = refine(cv, pi); r.nullAt = refine(cv, ni);
        double[] sorted = cv.clone(); Arrays.sort(sorted); double median = sorted[STEPS / 2];
        double prom = r.peakDb - median, deep = median - r.nullDb;
        if (r.depth < MIN_DEPTH_DB || r.depth < 3 * r.noise) { r.retry = Txt.s(R.string.modes_spin_flat, String.format(java.util.Locale.US, "%.0f", r.depth)); return r; }
        r.fromNull = deep > prom;
        r.bearing = r.fromNull ? (r.nullAt + 180) % 360 : r.peakAt;
        double width = width(cv, r.fromNull ? ni : pi, r.fromNull ? r.nullDb + r.depth / 2 : r.peakDb - r.depth / 2, !r.fromNull);
        r.sigma = Math.max(5, Math.min(40, 3 + 0.08 * width + 40 * r.noise / r.depth));
        r.ok = true;
        return r;
    }

    /** The curve at any heading, linearly between its steps. */
    static double at(double[] cv, double deg) {
        double x = ((deg % 360 + 360) % 360) / (360.0 / cv.length); int i = (int) x; double f = x - i;
        return cv[i % cv.length] * (1 - f) + cv[(i + 1) % cv.length] * f;
    }
    /** The extreme's heading refined by a parabola through it and its neighbours. */
    static double refine(double[] cv, int i) {
        int n = cv.length; double a = cv[(i - 1 + n) % n], b = cv[i], c = cv[(i + 1) % n], den = a - 2 * b + c;
        double off = Math.abs(den) < 1e-9 ? 0 : Math.max(-0.5, Math.min(0.5, 0.5 * (a - c) / den));
        return ((i + off) * 360.0 / n + 360) % 360;
    }
    /** Degrees around step {@code i} where the curve stays above (peak) or below (null) {@code level}. */
    static double width(double[] cv, int i, double level, boolean above) {
        int n = cv.length, w = 1;
        for (int s = 1; s < n / 2; s++) { double v = cv[(i + s) % n]; if (above ? v < level : v > level) break; w++; }
        for (int s = 1; s < n / 2; s++) { double v = cv[(i - s + n) % n]; if (above ? v < level : v > level) break; w++; }
        return w * 360.0 / n;
    }
}
