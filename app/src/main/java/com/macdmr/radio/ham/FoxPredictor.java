/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.ham;

import com.macdmr.radio.i18n.Txt;
import com.macdmr.radio.coverage.Predictor;
import com.macdmr.radio.coverage.Propagation;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the fox probably is, as a map: each layer gives every square of a grid around the marks a likelihood and the
 * layers that are on multiply.
 * <ul>
 * <li><b>Bearings</b>: a likelihood around each bearing line (the compass near the body is good to about 15 degrees,
 * or the mark's own quality); alone, the estimate is {@link FoxHunt#estimate}'s crossing, unchanged.</li>
 * <li><b>Signal heat map</b>: how the strength changes between marks. A fox at x gives mark i about
 * {@code P0 - 10 n log10 d_i} (dBm) with its level P0 unknown (solved per square in closed form) and the path-loss
 * exponent n fitted per hunt from {@link #EXPONENTS}; what is left over is shadowing of about 7 dB. Marks at the
 * radio's floor only say the fox is farther than that. Needs 3 or more marks spread over 100 m or more.</li>
 * <li><b>Terrain</b> (refines the heat map): the hills between each square and each mark add their diffraction loss
 * (the coverage maps' {@link Propagation}, over an {@link Predictor.Elevation}), so a square behind a ridge from a
 * strong mark drops out. Worked out on 100 m blocks first, then on the 25 m squares of the likeliest blocks; each
 * square-to-mark loss is cached, and the grid sits on a fixed lattice so a new mark reuses the old ones.</li>
 * </ul>
 * The peak of the product is the estimate; the 50 % and 90 % regions are its uncertainty. Plain Java, host tested.
 */
public final class FoxPredictor {
    private FoxPredictor() { }

    public static final double[] EXPONENTS = { 2.0, 2.5, 3.0, 3.5, 4.0 };
    /** Shadowing (dB, one sigma), bearing error (degrees), the module's floor (dBm: at or below = no real reading). */
    public static final double SIGMA_DB = 7, BEARING_SIGMA_DEG = 15, FLOOR_DBM = -118;
    /** The heat map needs marks this far apart (m); a square closer than this to a mark counts as this far (m). */
    public static final double MIN_SPREAD_M = 100, MIN_D_M = 10;
    /** Grid limits: never wider than this half-size (m), never more than this many squares a side. */
    static final double MAX_HALF_M = 16_000; static final int MAX_SIDE = 160;
    public static final int BEARINGS = 1, HEAT = 2, TERRAIN = 4;
    /** Terrain: coarse block (m), the share of the likelihood whose blocks are refined, and at most this many blocks. */
    static final double COARSE_M = 100, REFINE_MASS = 0.9; static final int REFINE_MAX = 120;
    /** The fox's antenna above ground (m) unless told otherwise: a handheld or a small box on the ground. */
    public static final double FOX_AGL = 1.5;

    public static final class Options {
        public boolean bearings = true, heat = true, terrain = false;
        /** The phone has no compass: its marks never carry a bearing (the note says so instead of "not yet"). */
        public boolean noCompass;
        /** Half the grid's side (m) at least, and its square (m); the grid grows to hold every mark and moves out when the peak sits at its edge. */
        public double halfM = 1000, cellM = 25, floorDbm = FLOOR_DBM, sigmaDb = SIGMA_DB;
        /** Terrain: ground height (null = none loaded for this area), the fox's antenna height (m), the frequency (Hz), the loss cache to reuse. */
        public Predictor.Elevation elevation; public double foxAgl = FOX_AGL, hz = 146e6; public TerrainCache cache;
    }

    /** Terrain losses already worked out, per mark and square (kept between predictions while the height, frequency and ground stay the same). */
    public static final class TerrainCache {
        private final Map<FoxHunt.Mark, Map<Long, Float>> loss = new HashMap<>();
        private double agl = Double.NaN, hz = Double.NaN; private Predictor.Elevation el; int computed;
        synchronized double get(Predictor.Elevation e, FoxHunt.Mark m, double lat, double lon, double foxAgl, double f) {
            if (e != el || foxAgl != agl || f != hz) { loss.clear(); el = e; agl = foxAgl; hz = f; }
            Map<Long, Float> pm = loss.get(m); if (pm == null) loss.put(m, pm = new HashMap<>());
            long k = ((long) Math.round(lat * 1e6) << 32) | (Math.round(lon * 1e6) & 0xFFFFFFFFL);
            Float v = pm.get(k);
            if (v == null) { v = (float) terrainLoss(e, lat, lon, m.lat, m.lon, foxAgl, f); pm.put(k, v); computed++; }
            return v;
        }
        /** How many losses were worked out (not taken from the cache) so far. */
        public synchronized int computed() { return computed; }
    }

    /**
     * What the hills between a fox at (fLat, fLon) and a mark add to the path loss (dB): the knife-edge diffraction of the
     * profile over that of smooth ground ({@link Propagation#terrainExcess}). Only the excess: the level and the fall-off
     * with distance are the heat map's own fit.
     */
    public static double terrainLoss(Predictor.Elevation el, double fLat, double fLon, double mLat, double mLon, double foxAgl, double hz) {
        double d = metres(fLat, fLon, mLat, mLon);
        if (d < MIN_D_M) return 0;
        int k = Math.max(3, Math.min(150, (int) Math.ceil(d / 40)) + 1);           // ~40 m samples (the tiles hold ~55 m), at most 150
        double[] prof = new double[k];
        for (int i = 0; i < k; i++) { double f = i / (double) (k - 1); prof[i] = el.at(fLat + (mLat - fLat) * f, fLon + (mLon - fLon) * f); }
        return Propagation.terrainExcess(prof, d / (k - 1), foxAgl, Predictor.RX_AGL, hz);
    }

    public static final class Result {
        /** The estimate: the combined peak (Bearings alone: the crossing, as before; nothing usable: the strongest mark). */
        public double lat, lon;
        /** Layers the estimate used ({@link #BEARINGS} | {@link #HEAT} | {@link #TERRAIN}); 0 = only the strongest mark. */
        public int layers;
        /** Why a layer that is on was left out, or null. */
        public String note;
        /** The heat map's path-loss exponent (NaN without it). */
        public double exponent = Double.NaN;
        /** Radius (m) of a circle as big as the 50 % region; NaN without a map. */
        public double r50M = Double.NaN;
        /** The map: nx * ny squares of cellM metres, row 0 at the south, column 0 at the west; p sums to 1. Null without one. */
        public float[] p; public int nx, ny; public double south, west, north, east, cellM;
        /** A square with p at or above thr50 is inside the 50 % region (thr90: the 90 % one). */
        public float thr50, thr90;
        public boolean hasMap() { return p != null; }
        /** rough / fair / good from the 50 % region's size; null without a map. */
        public String confidence() { return Double.isNaN(r50M) ? null : r50M <= 100 ? "good" : r50M <= 300 ? "fair" : "rough"; }   // i18n: key, see confidenceText
        /** {@link #confidence} in the app's language; null without a map. */
        public String confidenceText() { return Double.isNaN(r50M) ? null : Txt.s(r50M <= 100 ? com.macdmr.radio.R.string.modes_fox_conf_good : r50M <= 300 ? com.macdmr.radio.R.string.modes_fox_conf_fair : com.macdmr.radio.R.string.modes_fox_conf_rough); }
        /** "bearings + heat map" and so on. */
        public String layerNames() {
            List<String> names = new java.util.ArrayList<>();
            if ((layers & BEARINGS) != 0) names.add(Txt.s(com.macdmr.radio.R.string.modes_fox_layer_bearings));
            if ((layers & HEAT) != 0) names.add(Txt.s(com.macdmr.radio.R.string.modes_fox_layer_heat));
            if ((layers & TERRAIN) != 0) names.add(Txt.s(com.macdmr.radio.R.string.modes_fox_layer_terrain));
            return names.isEmpty() ? Txt.s(com.macdmr.radio.R.string.modes_fox_layer_strongest) : String.join(Txt.s(com.macdmr.radio.R.string.modes_fox_layer_separator), names);
        }
        public double cellLat(int iy) { return south + (iy + 0.5) * (north - south) / ny; }
        public double cellLon(int ix) { return west + (ix + 0.5) * (east - west) / nx; }
    }

    private static final double R = 6371000;

    /** Why the heat map cannot run on these marks, or null when it can. */
    public static String heatBlocker(List<FoxHunt.Mark> marks, double floorDbm) {
        if (marks.size() < 3) return Txt.s(com.macdmr.radio.R.string.modes_fox_block_marks);
        int above = 0; for (FoxHunt.Mark m : marks) if (m.dbm > floorDbm) above++;
        if (above < 2) return Txt.s(com.macdmr.radio.R.string.modes_fox_block_floor);
        double spread = 0;
        for (FoxHunt.Mark a : marks) for (FoxHunt.Mark b : marks) spread = Math.max(spread, metres(a.lat, a.lon, b.lat, b.lon));
        return spread < MIN_SPREAD_M ? Txt.s(com.macdmr.radio.R.string.modes_fox_block_spread) : null;
    }

    /** The prediction for these marks, or null with none. */
    public static Result predict(List<FoxHunt.Mark> marks, Options o) {
        if (marks == null || marks.isEmpty()) return null;
        Result r = new Result();
        FoxHunt.Estimate be = FoxHunt.estimate(marks);
        int nb = 0; for (FoxHunt.Mark m : marks) if (m.hasBearing()) nb++;
        boolean bear = o.bearings && nb > 0;
        String block = o.heat ? heatBlocker(marks, o.floorDbm) : null;
        boolean heat = o.heat && block == null;
        List<String> notes = new java.util.ArrayList<>();
        if (o.bearings && nb == 0) notes.add(Txt.s(o.noCompass ? com.macdmr.radio.R.string.modes_fox_note_no_compass : com.macdmr.radio.R.string.modes_fox_note_no_bearing));
        if (block != null) notes.add(block);
        boolean terr = heat && o.terrain && o.elevation != null;
        if (o.terrain && !o.heat) notes.add(Txt.s(com.macdmr.radio.R.string.modes_fox_note_terrain_off));
        else if (o.terrain && heat && o.elevation == null) notes.add(Txt.s(com.macdmr.radio.R.string.modes_fox_note_no_terrain));
        if (!notes.isEmpty()) r.note = String.join(Txt.s(com.macdmr.radio.R.string.modes_clause_separator), notes);
        r.lat = be.lat; r.lon = be.lon; r.layers = bear && be.fromBearings ? BEARINGS : 0;
        if (!bear && !heat) return r;

        // local metres from a fixed point near the marks (a 0.01 degree lattice, so the squares stay put as marks are added)
        double latC = 0, lonC = 0; for (FoxHunt.Mark m : marks) { latC += m.lat; lonC += m.lon; } latC /= marks.size(); lonC /= marks.size();
        double lat0 = Math.round(latC * 100) / 100.0, lon0 = Math.round(lonC * 100) / 100.0, cos = Math.cos(Math.toRadians(lat0));
        int n = marks.size(); double[] mx = new double[n], my = new double[n];
        double cx = Math.toRadians(lonC - lon0) * R * cos, cy = Math.toRadians(latC - lat0) * R, half = o.halfM;
        for (int i = 0; i < n; i++) { FoxHunt.Mark m = marks.get(i); mx[i] = Math.toRadians(m.lon - lon0) * R * cos; my[i] = Math.toRadians(m.lat - lat0) * R; half = Math.max(half, Math.max(Math.abs(mx[i] - cx), Math.abs(my[i] - cy)) + 500); }
        if (be.fromBearings) { double ex = Math.toRadians(be.lon - lon0) * R * cos, ey = Math.toRadians(be.lat - lat0) * R; half = Math.max(half, Math.max(Math.abs(ex - cx), Math.abs(ey - cy)) + 300); }
        half = Math.min(half, MAX_HALF_M);
        TerrainCache tc = terr ? (o.cache != null ? o.cache : new TerrainCache()) : null;

        double[] ll = null; int nx = 0; double cell = 0;
        for (int round = 0; round < 4; round++) {
            cell = Math.max(o.cellM, 2 * half / MAX_SIDE);
            int f = terr ? Math.max(1, (int) Math.round(Math.max(COARSE_M, 2 * half / 40) / cell)) : 1;   // squares per terrain block side (at most 40 blocks a side)
            nx = (int) Math.ceil(2 * half / (cell * f)) * f;
            ll = new double[nx * nx];
            double snap = cell * f, x0 = Math.floor((cx - nx * cell / 2) / snap) * snap, y0 = Math.floor((cy - nx * cell / 2) / snap) * snap;
            if (bear) bearingLayer(ll, marks, mx, my, nx, cell, x0, y0);
            if (heat) {
                double[] t = terr ? terrainGrid(ll, marks, mx, my, nx, f, cell, x0, y0, lat0, lon0, cos, o, tc) : null;
                r.exponent = heatLayer(ll, marks, mx, my, nx, cell, x0, y0, o, t);
            }
            r.south = lat0 + Math.toDegrees(y0 / R); r.north = lat0 + Math.toDegrees((y0 + nx * cell) / R);
            r.west = lon0 + Math.toDegrees(x0 / (R * cos)); r.east = lon0 + Math.toDegrees((x0 + nx * cell) / (R * cos));
            int pk = argmax(ll), px = pk % nx, py = pk / nx;
            if (!heat || half >= MAX_HALF_M || (px >= 2 && py >= 2 && px < nx - 2 && py < nx - 2)) break;
            cx = x0 + (px + 0.5) * cell; cy = y0 + (py + 0.5) * cell;   // the peak sits at the edge: a bigger grid around it
            half = Math.min(MAX_HALF_M, half * 2);
        }
        r.nx = r.ny = nx; r.cellM = cell;
        double max = Double.NEGATIVE_INFINITY; for (double v : ll) max = Math.max(max, v);
        float[] p = new float[ll.length]; double sum = 0;
        for (int i = 0; i < ll.length; i++) { double v = Math.exp(ll[i] - max); p[i] = (float) v; sum += v; }
        for (int i = 0; i < p.length; i++) p[i] /= sum;
        r.p = p;
        float[] s = p.clone(); Arrays.sort(s);
        double acc = 0; int in50 = s.length; r.thr50 = r.thr90 = s[0];
        for (int i = s.length - 1, k = 1; i >= 0; i--, k++) {     // most likely squares first
            acc += s[i];
            if (in50 == s.length && acc >= 0.5) { in50 = k; r.thr50 = s[i]; }
            if (acc >= 0.9) { r.thr90 = s[i]; break; }
        }
        r.r50M = Math.sqrt(in50 * cell * cell / Math.PI);
        if (heat || !be.fromBearings) {                             // the map's peak; Bearings alone keeps the crossing
            int pk = argmax(ll);
            r.lat = r.cellLat(pk / nx); r.lon = r.cellLon(pk % nx);
            r.layers = (heat ? HEAT : 0) | (bear ? BEARINGS : 0) | (terr ? TERRAIN : 0);
        }
        return r;
    }

    /** -diff^2 / 2 sigma^2 for each bearing; a square on top of the mark says nothing. */
    private static void bearingLayer(double[] ll, List<FoxHunt.Mark> marks, double[] mx, double[] my, int nx, double cell, double x0, double y0) {
        for (int i = 0; i < marks.size(); i++) {
            FoxHunt.Mark m = marks.get(i); if (!m.hasBearing()) continue;
            double sg = Double.isNaN(m.bearingSigma) || m.bearingSigma <= 0 ? BEARING_SIGMA_DEG : m.bearingSigma, k = 1 / (2 * sg * sg);
            for (int iy = 0, c = 0; iy < nx; iy++) {
                double dy = y0 + (iy + 0.5) * cell - my[i];
                for (int ix = 0; ix < nx; ix++, c++) {
                    double dx = x0 + (ix + 0.5) * cell - mx[i];
                    if (dx * dx + dy * dy < cell * cell / 4) continue;
                    double diff = Math.toDegrees(Math.atan2(dx, dy)) - m.bearing;
                    diff = ((diff + 180) % 360 + 360) % 360 - 180;
                    ll[c] -= diff * diff * k;
                }
            }
        }
    }

    /**
     * Terrain loss for every square and mark ({@code [square * marks + mark]}): each 100 m block takes the loss at its middle;
     * then the blocks holding the likeliest {@link #REFINE_MASS} of a first heat map (with the bearings already in {@code ll})
     * get every square worked out.
     */
    private static double[] terrainGrid(double[] ll, List<FoxHunt.Mark> marks, double[] mx, double[] my, int nx, int f, double cell, double x0, double y0,
                                        double lat0, double lon0, double cos, Options o, TerrainCache tc) {
        int n = marks.size(), nb = nx / f; double[] t = new double[nx * nx * n];
        for (int by = 0; by < nb; by++)
            for (int bx = 0; bx < nb; bx++) {
                double y = y0 + (by + 0.5) * f * cell, x = x0 + (bx + 0.5) * f * cell;
                double la = lat0 + Math.toDegrees(y / R), lo = lon0 + Math.toDegrees(x / (R * cos));
                for (int i = 0; i < n; i++) {
                    double v = tc.get(o.elevation, marks.get(i), la, lo, o.foxAgl, o.hz);
                    for (int iy = by * f; iy < by * f + f; iy++) for (int ix = bx * f; ix < bx * f + f; ix++) t[(iy * nx + ix) * n + i] = v;
                }
            }
        if (f == 1) return t;
        double[] first = ll.clone(); heatLayer(first, marks, mx, my, nx, cell, x0, y0, o, t);
        double max = Double.NEGATIVE_INFINITY; for (double v : first) max = Math.max(max, v);
        double[] mass = new double[nb * nb]; double sum = 0;
        for (int c = 0; c < first.length; c++) { double v = Math.exp(first[c] - max); mass[(c / nx / f) * nb + (c % nx) / f] += v; sum += v; }
        Integer[] order = new Integer[mass.length]; for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Double.compare(mass[b], mass[a]));
        double acc = 0;
        for (int k = 0; k < order.length && k < REFINE_MAX && acc < REFINE_MASS * sum; k++) {
            int b = order[k], by = b / nb, bx = b % nb; acc += mass[b];
            for (int iy = by * f; iy < by * f + f; iy++)
                for (int ix = bx * f; ix < bx * f + f; ix++) {
                    double la = lat0 + Math.toDegrees((y0 + (iy + 0.5) * cell) / R), lo = lon0 + Math.toDegrees((x0 + (ix + 0.5) * cell) / (R * cos));
                    for (int i = 0; i < n; i++) t[(iy * nx + ix) * n + i] = tc.get(o.elevation, marks.get(i), la, lo, o.foxAgl, o.hz);
                }
        }
        return t;
    }

    /** Adds the heat map's log likelihood for the best exponent and returns that exponent; {@code t}: terrain loss per square and mark, or null. */
    private static double heatLayer(double[] ll, List<FoxHunt.Mark> marks, double[] mx, double[] my, int nx, double cell, double x0, double y0, Options o, double[] t) {
        int n = marks.size(), cells = nx * nx;
        double k = 1 / (2 * o.sigmaDb * o.sigmaDb);
        double[] lg = new double[cells * n];                         // log10 distance, square by square
        for (int iy = 0, c = 0; iy < nx; iy++) {
            double y = y0 + (iy + 0.5) * cell;
            for (int ix = 0; ix < nx; ix++, c++) {
                double x = x0 + (ix + 0.5) * cell;
                for (int i = 0; i < n; i++) lg[c * n + i] = Math.log10(Math.max(MIN_D_M, Math.hypot(x - mx[i], y - my[i])));
            }
        }
        double[] best = null; double bestEv = Double.NEGATIVE_INFINITY, bestN = EXPONENTS[0];
        for (double e : EXPONENTS) {
            double[] h = new double[cells]; double mx2 = Double.NEGATIVE_INFINITY;
            for (int c = 0; c < cells; c++) {
                double s = 0, s2 = 0; int cnt = 0;
                for (int i = 0; i < n; i++) { FoxHunt.Mark m = marks.get(i); if (m.dbm <= o.floorDbm) continue; double v = m.dbm + 10 * e * lg[c * n + i] + (t == null ? 0 : t[c * n + i]); s += v; s2 += v * v; cnt++; }
                double p0 = s / cnt, rss = Math.max(0, s2 - s * s / cnt);
                for (int i = 0; i < n; i++) {                          // at the floor: the fox is at least this far, so a louder prediction is wrong
                    FoxHunt.Mark m = marks.get(i); if (m.dbm > o.floorDbm) continue;
                    double pred = p0 - 10 * e * lg[c * n + i] - (t == null ? 0 : t[c * n + i]);
                    if (pred > o.floorDbm) rss += (pred - o.floorDbm) * (pred - o.floorDbm);
                }
                h[c] = -rss * k; mx2 = Math.max(mx2, h[c]);
            }
            double ev = 0; for (double v : h) ev += Math.exp(v - mx2); ev = mx2 + Math.log(ev);    // how well this exponent explains the marks over the whole grid
            if (ev > bestEv) { bestEv = ev; best = h; bestN = e; }
        }
        for (int c = 0; c < cells; c++) ll[c] += best[c];
        return bestN;
    }

    private static int argmax(double[] a) { int b = 0; for (int i = 1; i < a.length; i++) if (a[i] > a[b]) b = i; return b; }

    /** Metres between two points (flat earth: fine over a hunt). */
    public static double metres(double la, double lo, double la2, double lo2) {
        double x = Math.toRadians(lo2 - lo) * R * Math.cos(Math.toRadians((la + la2) / 2)), y = Math.toRadians(la2 - la) * R; return Math.hypot(x, y);
    }

    /** Bearing in degrees true from the first point to the second. */
    public static double bearing(double la, double lo, double la2, double lo2) {
        double x = Math.toRadians(lo2 - lo) * Math.cos(Math.toRadians((la + la2) / 2)), y = Math.toRadians(la2 - la);
        return (Math.toDegrees(Math.atan2(x, y)) + 360) % 360;
    }
}
