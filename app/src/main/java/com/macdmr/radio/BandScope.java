/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Band scope: a receive-only sweep across a frequency range. At each step the radio is retuned with one analog channel
 * write (squelch closed, nothing keyed), allowed to settle, and its signal strength is read (0x32); a full sweep is one
 * line of the waterfall. While it runs the received audio is dropped, the other signal polls and the coverage log pause,
 * and when it stops the channel the radio was on is written back. It refuses to run near a tracked satellite pass and
 * stops by itself for Talk and for a scheduled operation.
 */
public final class BandScope {
    public static final class Preset { public final String name; public final int from, to, step; Preset(String n, int f, int t, int s) { name = n; from = f; to = t; step = s; } }
    public static final Preset[] PRESETS = {
        new Preset("2 m", 144_000_000, 148_000_000, 25_000),
        new Preset("2 m FM simplex", 146_400_000, 147_600_000, 15_000),
        new Preset("70 cm", 420_000_000, 450_000_000, 100_000),
        new Preset("70 cm FM", 440_000_000, 450_000_000, 25_000),
        new Preset("GMRS / FRS", 462_500_000, 467_750_000, 12_500),
        new Preset("Marine VHF", 156_000_000, 162_025_000, 25_000),
        new Preset("NOAA weather", 162_400_000, 162_550_000, 25_000),
    };
    public static final int MAX_STEPS = 1200, HISTORY = 120;
    /** The presets offered for a region (Device → Region): the NOAA weather band only where weather radio exists, the rest under Show all. Indexes into PRESETS. */
    public static java.util.List<Integer> offered(com.macdmr.radio.region.Region r, boolean all) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (int i = 0; i < PRESETS.length; i++) if (all || !weatherPreset(PRESETS[i]) || r.weatherRadio) out.add(i);
        return out;
    }
    static boolean weatherPreset(Preset p) { return p.from >= 162_400_000 && p.to <= 162_550_000; }

    public interface Hooks {
        /** Retune for a sweep step (one analog write, squelch closed). */
        void tune(int hz) throws Exception;
        /** Ask the module for the signal strength (the reply comes back through {@link #onRssi}). */
        void query() throws Exception;
        /** Put the radio back on its channel (once, when the sweep stops). */
        void restore();
        void sweep(int from, int step, float[] dbm, int n);
        void state(String s);
        /** The phone's reading scale and timing ({@link DeviceProfile#rssiStepDb}, {@link DeviceProfile#scopeReadMs}); the defaults are the Armor's. */
        default double stepDb() { return 0.5; }
        default double offsetDb() { return 0; }
        default int readMs() { return 300; }
    }

    private final Hooks hooks;
    private final LinkedBlockingQueue<Integer> replies = new LinkedBlockingQueue<>();
    private volatile Thread thread;
    private volatile int from, to, step, settleMs = DEFAULT_SETTLE_MS;
    /** Measured 2026-10-22: the module's signal reading is 15-20 dB low until about 0.7 s after a retune. */
    public static final int DEFAULT_SETTLE_MS = 700;
    private volatile float[] partial; private volatile int partialIdx = -1;
    /** The sweep in progress (NaN past {@link #partialIndex()}), or null. */
    public float[] partial() { float[] p = partial; return p == null ? null : p.clone(); }
    public int partialIndex() { return partialIdx; }
    private final ArrayDeque<float[]> history = new ArrayDeque<>();
    private float[] last, peak;
    private volatile long sweepMs;
    private volatile int timeoutMs = 1200;
    private volatile int[] latency = new int[0];
    /** How long each step's reading took to arrive (ms, -1 = none), for the bench. */
    public int[] latency() { return latency.clone(); }
    public void setTimeoutMs(int ms) { timeoutMs = Math.max(150, Math.min(2000, ms)); }

    public BandScope(Hooks h) { hooks = h; }

    public boolean running() { return thread != null; }
    public int from() { return from; } public int to() { return to; } public int step() { return step; } public int settleMs() { return settleMs; }
    public long sweepMs() { return sweepMs; }
    private volatile long sweepStart; private volatile int currentHz;
    /** The frequency being measured right now (0 when not sweeping). */
    public int currentHz() { return thread != null ? currentHz : 0; }
    /** How long a whole sweep of this range takes: the last full sweep, else the steps so far scaled up, else a guess
     *  (settle time plus a typical 0.3 s for the reading). */
    public long estimateMs() {
        if (sweepMs > 0) return sweepMs;
        int n = steps(from, to, step), done = partialIdx + 1;
        if (thread != null && done >= 3) return (System.currentTimeMillis() - sweepStart) * n / done;
        return (long) n * (settleMs + hooks.readMs());
    }
    /** "about 5 min", "40 s": for the screens. */
    public static String duration(long ms) { long s = Math.round(ms / 1000.0); return s < 90 ? s + " s" : s < 5400 ? Math.round(s / 60.0) + " min" : String.format(java.util.Locale.US, "%.1f h", s / 3600.0); }
    /** The signal strength reply (raw byte) while sweeping. */
    public void onRssi(int raw) { if (thread != null) replies.offer(raw); }

    /** Steps between {@code from} and {@code to} inclusive, and whether the range is one the radio can tune. Pure. */
    public static int steps(int from, int to, int step) { return step <= 0 || to < from ? 0 : (to - from) / step + 1; }
    public static boolean tunable(int hz) { return com.macdmr.radio.DeviceProfile.inBands(hz); }
    /** Raw RSSI byte to dBm, as the module reports it (0 = no reading). */
    public static float dbm(int raw) { return dbm(raw, 0.5, 0); }
    /** A sweep reading in dBm on a module with this step and offset; above -20 dBm is the module's "no reading" (raw 200+ on the Armor). */
    public static float dbm(int raw, double stepDb, double offsetDb) { if (raw <= 0) return Float.NaN; float d = (float) RadioEngine.rssiDbm(raw, stepDb, offsetDb); return d < -20 ? d : Float.NaN; }

    /** Whether {@code from} and {@code to} lie in the same band of the profile. */
    static boolean oneBand(com.macdmr.radio.DeviceProfile dp, long from, long to) {
        for (int i = 0; i + 1 < dp.bandsHz.length; i += 2) if (from >= dp.bandsHz[i] && to <= dp.bandsHz[i + 1]) return true;
        return false;
    }

    /** Why a range cannot be swept, or null. Pure. */
    public static String problem(int from, int to, int step) {
        if (from <= 0 || to <= 0 || step <= 0) return "Enter the frequencies in MHz and the step in kHz";
        if (step < 2_500) return "Use a step of at least 2.5 kHz";
        if (to <= from) return "The end must be above the start";
        com.macdmr.radio.DeviceProfile dp = com.macdmr.radio.DeviceProfile.detect();
        if (!tunable(from) || !tunable(to) || !oneBand(dp, from, to)) return "The radio tunes " + dp.bandsText() + ": keep the range inside one of them";
        int n = steps(from, to, step);
        if (n < 2) return "Choose a range with at least two steps";
        if (n > MAX_STEPS) return n + " steps is too many (up to " + MAX_STEPS + "): use a larger step";
        return null;
    }

    /** Start sweeping (null), or why not. */
    public synchronized String start(int from, int to, int step, int settleMs) {
        if (thread != null) stop();
        String bad = problem(from, to, step); if (bad != null) return bad;
        this.from = from; this.to = to; this.step = step; this.settleMs = Math.max(10, Math.min(3000, settleMs));
        synchronized (history) { history.clear(); last = null; peak = null; }
        sweepMs = 0; partialIdx = -1; currentHz = from;                    // a new range: forget the last range's timing
        replies.clear();
        Thread t = new Thread(this::loop, "band-scope"); thread = t; t.setPriority(Thread.NORM_PRIORITY + 1); t.start();
        hooks.state("sweeping");
        return null;
    }

    /** Stop and put the radio back; returns once the sweep thread has ended. */
    public void stop() {
        Thread t; synchronized (this) { t = thread; thread = null; }
        if (t == null) return;
        t.interrupt();
        try { t.join(3000); } catch (InterruptedException ignored) { }
        hooks.restore();
        hooks.state("stopped");
    }

    private void loop() {
        int n = steps(from, to, step);
        final float[] row = new float[n]; int[] lat = new int[n];
        try {
            while (thread == Thread.currentThread()) {
                long t0 = System.currentTimeMillis(); sweepStart = t0;
                java.util.Arrays.fill(row, Float.NaN); partial = row; partialIdx = -1;
                for (int i = 0; i < n && thread == Thread.currentThread(); i++) {
                    currentHz = from + i * step;
                    hooks.tune(currentHz);
                    Thread.sleep(settleMs);
                    replies.clear(); long q = System.currentTimeMillis(); hooks.query();
                    Integer raw = replies.poll(timeoutMs, TimeUnit.MILLISECONDS);      // the module answers after about 0.1 s or 0.55 s (its measuring cycle)
                    row[i] = raw == null ? Float.NaN : dbm(raw, hooks.stepDb(), hooks.offsetDb()); lat[i] = raw == null ? -1 : (int) (System.currentTimeMillis() - q); partialIdx = i;
                }
                if (thread != Thread.currentThread()) break;
                sweepMs = System.currentTimeMillis() - t0; latency = lat.clone();
                float[] copy = row.clone(); partialIdx = -1;
                synchronized (history) {
                    last = copy; history.addLast(copy); while (history.size() > HISTORY) history.removeFirst();
                    if (peak == null) peak = copy.clone(); else for (int i = 0; i < n; i++) if (!(copy[i] <= peak[i])) peak[i] = Float.isNaN(peak[i]) ? copy[i] : Math.max(peak[i], copy[i]);
                }
                hooks.sweep(from, step, copy, n);
            }
        } catch (InterruptedException ignored) {
        } catch (Throwable x) { hooks.state("stopped: " + x.getMessage()); }
    }

    public float[] last() { synchronized (history) { return last == null ? null : last.clone(); } }
    public float[] peak() { synchronized (history) { return peak == null ? null : peak.clone(); } }
    public List<float[]> history() { synchronized (history) { return new ArrayList<>(history); } }
    public void clearPeak() { synchronized (history) { peak = last == null ? null : last.clone(); } }

    /** Frequencies standing out above the noise: the median of the sweep + {@code db}, local maxima only. Pure. */
    public static List<int[]> peaks(float[] dbm, int from, int step, float db) {
        List<int[]> out = new ArrayList<>(); if (dbm == null) return out;
        float[] s = dbm.clone(); java.util.Arrays.sort(s); int k = 0; while (k < s.length && Float.isNaN(s[s.length - 1 - k])) k++;
        float median = s.length - k > 0 ? s[(s.length - k) / 2] : Float.NaN; if (Float.isNaN(median)) return out;
        for (int i = 0; i < dbm.length; i++) {
            float v = dbm[i]; if (Float.isNaN(v) || v < median + db) continue;
            if ((i > 0 && dbm[i - 1] > v) || (i + 1 < dbm.length && dbm[i + 1] >= v)) continue;
            out.add(new int[] { from + i * step, Math.round(v) });
        }
        return out;
    }
}
