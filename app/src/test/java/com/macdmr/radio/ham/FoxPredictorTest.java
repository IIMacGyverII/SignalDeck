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
import java.util.Random;

public class FoxPredictorTest {
    private static final double LAT = 45.2, LON = -93.5, FOX_LAT = LAT + 0.004, FOX_LON = LON + 0.005;   // the fox ~450 m north, ~390 m east

    /** A point east/north metres from (LAT, LON). */
    static double[] at(double east, double north) { return new double[] { LAT + Math.toDegrees(north / 6371000), LON + Math.toDegrees(east / (6371000 * Math.cos(Math.toRadians(LAT)))) }; }

    /** Free-space-like readings (n = 2, P0 -40 dBm at 1 m) with the given shadowing (dB, seeded). */
    static List<FoxHunt.Mark> walk(double[][] spots, double noiseDb, double bearingErr, boolean bearings, long seed) {
        Random rnd = new Random(seed); List<FoxHunt.Mark> out = new ArrayList<>();
        for (double[] s : spots) {
            double[] p = at(s[0], s[1]);
            double d = FoxPredictor.metres(p[0], p[1], FOX_LAT, FOX_LON);
            int dbm = (int) Math.round(-40 - 20 * Math.log10(d) + rnd.nextGaussian() * noiseDb);
            double b = bearings ? FoxPredictor.bearing(p[0], p[1], FOX_LAT, FOX_LON) + rnd.nextGaussian() * bearingErr : Double.NaN;
            out.add(new FoxHunt.Mark(p[0], p[1], dbm, b, 0));
        }
        return out;
    }
    static final double[][] AROUND = { { 0, 0 }, { 600, 100 }, { 700, 800 }, { 100, 900 }, { -200, 400 }, { 350, 200 }, { 500, 600 } };

    static FoxPredictor.Options opts(boolean bearings, boolean heat) { FoxPredictor.Options o = new FoxPredictor.Options(); o.bearings = bearings; o.heat = heat; return o; }
    static double err(FoxPredictor.Result r) { return FoxPredictor.metres(r.lat, r.lon, FOX_LAT, FOX_LON); }

    @Test public void heatMapAloneFindsTheFoxInFreeSpace() {
        FoxPredictor.Result r = FoxPredictor.predict(walk(AROUND, 0, 0, false, 1), opts(false, true));
        assertTrue(r.hasMap());
        assertEquals(FoxPredictor.HEAT, r.layers);
        assertEquals(2.0, r.exponent, 1e-9);
        assertTrue("within 40 m, was " + err(r), err(r) < 40);
        assertEquals("strength alone is rough even without shadowing", "rough", r.confidence());
        // with 4 dB of shadowing it is rougher but still close
        FoxPredictor.Result n = FoxPredictor.predict(walk(AROUND, 4, 0, false, 7), opts(false, true));
        assertTrue("within 200 m, was " + err(n), err(n) < 200);
        float in50 = 0; for (float v : n.p) if (v >= n.thr50) in50 += v;
        assertTrue("the 50 % region holds about half", in50 >= 0.5f && in50 < 0.6f);
    }

    @Test public void bearingsAloneIsTodaysCrossing() {
        List<FoxHunt.Mark> ms = walk(AROUND, 3, 5, true, 3);
        FoxHunt f = new FoxHunt(); for (FoxHunt.Mark m : ms) f.add(m);
        FoxHunt.Estimate e = f.estimate();
        FoxPredictor.Result r = FoxPredictor.predict(ms, opts(true, false));
        assertEquals(e.lat, r.lat, 0); assertEquals(e.lon, r.lon, 0);
        assertEquals(FoxPredictor.BEARINGS, r.layers);
        assertTrue(r.hasMap());
        // nothing on: the strongest mark, as before
        FoxPredictor.Result none = FoxPredictor.predict(ms, opts(false, false));
        assertFalse(none.hasMap()); assertEquals(0, none.layers); assertEquals(e.lat, none.lat, 0);
    }

    @Test public void combinedIsAtLeastAsGoodAsEachAlone() {
        // marks around the fox, 4 dB shadowing and 15 degree compass errors, over 20 hunts
        double eb = 0, eh = 0, ec = 0, rb = 0, rh = 0, rc = 0;
        for (long seed = 1; seed <= 20; seed++) {
            List<FoxHunt.Mark> ms = walk(AROUND, 4, 15, true, seed);
            FoxPredictor.Result b = FoxPredictor.predict(ms, opts(true, false)), h = FoxPredictor.predict(ms, opts(false, true)), c = FoxPredictor.predict(ms, opts(true, true));
            assertEquals(FoxPredictor.BEARINGS | FoxPredictor.HEAT, c.layers);
            eb += err(b); eh += err(h); ec += err(c); rb += b.r50M; rh += h.r50M; rc += c.r50M;
        }
        assertTrue("combined " + ec / 20 + " m vs bearings " + eb / 20, ec <= eb);
        assertTrue("combined " + ec / 20 + " m vs heat map " + eh / 20, ec <= eh);
        assertTrue("combined region no bigger: " + rc / 20 + " vs " + rb / 20 + " / " + rh / 20, rc <= rb && rc <= rh);
    }

    static double massSouthOf(FoxPredictor.Result r, double lat) { double m = 0; for (int iy = 0; iy < r.ny; iy++) if (r.cellLat(iy) < lat) for (int ix = 0; ix < r.nx; ix++) m += r.p[iy * r.nx + ix]; return m; }

    @Test public void floorMarksSayFartherThanHere() {
        // a weaker fox (-60 dBm at 1 m: at the floor beyond ~800 m); marks on a line south of it cannot tell north from south
        List<FoxHunt.Mark> ms = new ArrayList<>();
        for (double[] s : new double[][] { { 0, 200 }, { 150, 210 }, { 300, 200 }, { 600, 200 } }) {
            double[] p = at(s[0], s[1]); ms.add(new FoxHunt.Mark(p[0], p[1], (int) Math.round(-60 - 20 * Math.log10(FoxPredictor.metres(p[0], p[1], FOX_LAT, FOX_LON))), Double.NaN, 0));
        }
        double line = at(0, 200)[0];
        FoxPredictor.Result plain = FoxPredictor.predict(ms, opts(false, true));
        assertEquals("mirror image: half the map on each side", 0.5, massSouthOf(plain, line), 0.05);
        double[] far = at(390, -350);                            // south of the line, 800 m from the fox: nothing heard there
        ms.add(new FoxHunt.Mark(far[0], far[1], -119, Double.NaN, 0));
        FoxPredictor.Result with = FoxPredictor.predict(ms, opts(false, true));
        assertTrue("the south side loses weight, was " + massSouthOf(with, line), massSouthOf(with, line) < 0.4);
        assertTrue("the peak is on the fox's side", with.lat > line);
        assertTrue("near the fox, was " + err(with), err(with) < 100);
        // a floor mark does not count toward the heat map's minimum
        List<FoxHunt.Mark> two = new ArrayList<>(ms.subList(0, 1)); two.add(ms.get(3)); two.add(ms.get(4));
        assertTrue(FoxPredictor.heatBlocker(two, FoxPredictor.FLOOR_DBM) == null);
        two.set(1, new FoxHunt.Mark(far[0] + 0.001, far[1], -125, Double.NaN, 0));
        assertNotNull(FoxPredictor.heatBlocker(two, FoxPredictor.FLOOR_DBM));
    }

    @Test public void tooFewOrTooCloseMarksGiveNoHeatMap() {
        List<FoxHunt.Mark> two = walk(new double[][] { { 0, 0 }, { 300, 0 } }, 0, 0, false, 1);
        FoxPredictor.Result r = FoxPredictor.predict(two, opts(false, true));
        assertFalse(r.hasMap()); assertEquals(0, r.layers); assertTrue(r.note.contains("3 marks"));
        List<FoxHunt.Mark> close = walk(new double[][] { { 0, 0 }, { 30, 10 }, { 50, 40 }, { 20, 60 } }, 0, 0, false, 1);
        r = FoxPredictor.predict(close, opts(false, true));
        assertFalse(r.hasMap()); assertTrue(r.note.contains("100 m"));
        // bearings still work on the close marks
        r = FoxPredictor.predict(walk(new double[][] { { 0, 0 }, { 30, 10 }, { 80, 40 } }, 0, 1, true, 2), opts(true, true));
        assertEquals(FoxPredictor.BEARINGS, r.layers);
        assertNull(FoxPredictor.predict(new ArrayList<>(), opts(true, true)));
    }

    @Test public void aPhoneWithoutACompassIsToldWhy() {
        List<FoxHunt.Mark> two = walk(new double[][] { { 0, 0 }, { 300, 0 } }, 0, 0, false, 1);   // marks without a bearing
        FoxPredictor.Options o = opts(true, true);
        assertTrue(FoxPredictor.predict(two, o).note.contains("no marks with a bearing yet"));
        o.noCompass = true;
        String note = FoxPredictor.predict(two, o).note;
        assertTrue(note, note.contains("no compass") && !note.contains("yet;"));
    }

    @Test public void peakAtTheEdgeGrowsTheGrid() {
        double[][] spots = { { 0, 0 }, { 150, 0 }, { 300, 0 }, { 150, 150 } };    // a small patch far from the fox
        List<FoxHunt.Mark> ms = new ArrayList<>();
        double[] fox = at(3000, 3000);
        for (double[] s : spots) { double[] p = at(s[0], s[1]); ms.add(new FoxHunt.Mark(p[0], p[1], (int) Math.round(-40 - 20 * Math.log10(FoxPredictor.metres(p[0], p[1], fox[0], fox[1]))), Double.NaN, 0)); }
        FoxPredictor.Result r = FoxPredictor.predict(ms, opts(false, true));
        assertTrue("the grid reaches past 2 km, was " + (r.north - r.south) * 111000, (r.north - r.south) * 111_000 > 3000);
        assertEquals("rough", r.confidence());
    }

    // ---- terrain ------------------------------------------------------------------------------------------

    /** A north-south ridge 70 m high and ~120 m wide, 250 m east of (LAT, LON); flat elsewhere. No download. */
    static final com.macdmr.radio.coverage.Predictor.Elevation RIDGE = (la, lo) -> {
        double x = Math.toRadians(lo - LON) * 6371000 * Math.cos(Math.toRadians(LAT)) - 250;
        return 70 * Math.exp(-x * x / (2 * 60 * 60));
    };
    static final double[][] ACROSS = { { -400, 300 }, { -300, -400 }, { -100, 600 }, { 500, 0 }, { 600, 400 }, { 450, -400 }, { 800, 100 }, { 700, -200 } };

    /** Readings from a fox at (0, 0): n = 2 plus what the ridge takes away. */
    static List<FoxHunt.Mark> overTheRidge() {
        double[] fox = at(0, 0); List<FoxHunt.Mark> out = new ArrayList<>();
        for (double[] s : ACROSS) {
            double[] p = at(s[0], s[1]);
            double l = FoxPredictor.terrainLoss(RIDGE, fox[0], fox[1], p[0], p[1], 1.5, 146e6);
            out.add(new FoxHunt.Mark(p[0], p[1], (int) Math.round(-40 - 20 * Math.log10(FoxPredictor.metres(p[0], p[1], fox[0], fox[1])) - l), Double.NaN, 0));
        }
        return out;
    }

    @Test public void theRidgeCostsTheMarksBehindIt() {
        double[] fox = at(0, 0), west = at(-300, -400), east = at(500, 0);
        assertTrue("a clear path loses little", Math.abs(FoxPredictor.terrainLoss(RIDGE, fox[0], fox[1], west[0], west[1], 1.5, 146e6)) < 3);
        assertTrue("the ridge costs 10 dB or more", FoxPredictor.terrainLoss(RIDGE, fox[0], fox[1], east[0], east[1], 1.5, 146e6) > 10);
        double low = FoxPredictor.terrainLoss(RIDGE, fox[0], fox[1], east[0], east[1], 1.5, 146e6), high = FoxPredictor.terrainLoss(RIDGE, fox[0], fox[1], east[0], east[1], 30, 146e6);
        assertTrue("the fox's antenna height matters: " + high + " vs " + low, Math.abs(high - low) > 1);
    }

    @Test public void terrainFindsTheFoxBehindARidge() {
        List<FoxHunt.Mark> ms = overTheRidge();
        double[] fox = at(0, 0);
        FoxPredictor.Options plain = opts(false, true), terr = opts(false, true);
        terr.terrain = true; terr.elevation = RIDGE; terr.cache = new FoxPredictor.TerrainCache();
        FoxPredictor.Result a = FoxPredictor.predict(ms, plain), b = FoxPredictor.predict(ms, terr);
        double ea = FoxPredictor.metres(a.lat, a.lon, fox[0], fox[1]), eb = FoxPredictor.metres(b.lat, b.lon, fox[0], fox[1]);
        assertEquals(FoxPredictor.HEAT | FoxPredictor.TERRAIN, b.layers);
        assertTrue("with terrain within 75 m (three squares), was " + eb, eb < 75);
        assertTrue("terrain helps: " + eb + " vs " + ea, eb < ea / 2);
        // the cache: one more mark only works out the new paths (the grid sits on the same lattice)
        int first = terr.cache.computed();
        double[] q = at(-200, 100); ms.add(new FoxHunt.Mark(q[0], q[1], (int) Math.round(-40 - 20 * Math.log10(FoxPredictor.metres(q[0], q[1], fox[0], fox[1]))), Double.NaN, 0));
        FoxPredictor.predict(ms, terr);
        int second = terr.cache.computed() - first;
        assertTrue("reused the cache: " + second + " new vs " + first, second < first / 2);
    }

    @Test public void terrainWithoutGroundOrHeatMapSaysSo() {
        List<FoxHunt.Mark> ms = overTheRidge();
        FoxPredictor.Options o = opts(false, true); o.terrain = true;                      // nothing loaded for the area
        FoxPredictor.Result r = FoxPredictor.predict(ms, o);
        assertEquals(FoxPredictor.HEAT, r.layers); assertTrue(r.note.contains("no terrain for this area yet"));
        o = opts(true, false); o.terrain = true; o.elevation = RIDGE;
        r = FoxPredictor.predict(ms, o);
        assertEquals(0, r.layers & FoxPredictor.TERRAIN); assertTrue(r.note.contains("heat map"));
    }
}
