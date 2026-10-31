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
import java.util.function.DoubleUnaryOperator;

public class SpinBearingTest {
    /** Body shadow: a cardioid with a 20 dB null behind the hunter, around -80 dBm toward the fox. */
    static DoubleUnaryOperator cardioid(double fox) { return h -> { double dd = Math.toRadians(h - fox); return -80 + 10 * Math.log10(0.99 * 0.5 * (1 + Math.cos(dd)) + 0.01); }; }

    /** Readings at the given headings, rounded to whole dBm like the module's, with Gaussian noise. */
    static List<double[]> sweep(double[] headings, DoubleUnaryOperator p, double noise, long seed) {
        Random rnd = new Random(seed); List<double[]> out = new ArrayList<>(); long t = 0;
        for (double h : headings) out.add(new double[] { t += 500, (h % 360 + 360) % 360, Math.round(p.applyAsDouble(h) + rnd.nextGaussian() * noise) });
        return out;
    }
    static double[] even(double from, double span, int n) { double[] h = new double[n]; for (int i = 0; i < n; i++) h[i] = from + span * i / n; return h; }
    static double err(double a, double b) { return Math.abs(FoxSignalGate.diff(a, b)); }

    @Test public void cardioidWithNoiseFindsTheFox() {
        int worst = 0;
        for (int s = 0; s < 20; s++) {
            double fox = 37 * s % 360;
            SpinBearing.Result r = SpinBearing.fit(sweep(even(s * 13, 360, 60), cardioid(fox), 2, s));
            assertTrue(r.retry, r.ok);
            worst = (int) Math.max(worst, err(r.bearing, fox));
            assertTrue("sigma " + r.sigma, r.sigma >= 5 && r.sigma <= 30);
        }
        assertTrue("worst error " + worst, worst <= 15);
    }

    @Test public void unevenTurnSpeedDoesNotBiasIt() {
        double[] a = even(0, 180, 90), b = even(180, 180, 20), h = new double[110];
        System.arraycopy(a, 0, h, 0, 90); System.arraycopy(b, 0, h, 90, 20);
        for (double fox : new double[] { 40, 150, 250, 320 }) {
            SpinBearing.Result r = SpinBearing.fit(sweep(h, cardioid(fox), 1.5, (long) fox));
            assertTrue(r.retry, r.ok);
            assertTrue("fox " + fox + " got " + r.bearing, err(r.bearing, fox) <= 15);
        }
    }

    @Test public void partialCoverageAsksForARetry() {
        SpinBearing.Result r = SpinBearing.fit(sweep(even(0, 250, 50), cardioid(100), 1, 1));
        assertFalse(r.ok); assertTrue(r.retry, r.retry.startsWith("Not a full circle"));
        assertFalse(SpinBearing.fit(sweep(even(0, 360, 8), cardioid(100), 1, 1)).ok);   // too few readings
    }

    @Test public void sharpNullIsUsedWhenDeeper() {
        double fox = 210;   // the null sits behind the hunter, 180 degrees from the fox; the front is flat
        DoubleUnaryOperator p = h -> { double dd = FoxSignalGate.diff(h, fox + 180); return -70 - 18 * Math.exp(-dd * dd / (2 * 15 * 15)); };
        SpinBearing.Result r = SpinBearing.fit(sweep(even(5, 360, 72), p, 1.5, 7));
        assertTrue(r.retry, r.ok); assertTrue(r.fromNull);
        assertTrue("got " + r.bearing, err(r.bearing, fox) <= 8);
    }

    @Test public void sharpPeakIsUsedWhenStronger() {
        double fox = 300;
        DoubleUnaryOperator p = h -> { double dd = FoxSignalGate.diff(h, fox); return -90 + 18 * Math.exp(-dd * dd / (2 * 25 * 25)); };
        SpinBearing.Result r = SpinBearing.fit(sweep(even(0, 360, 72), p, 1.5, 9));
        assertTrue(r.retry, r.ok); assertFalse(r.fromNull);
        assertTrue("got " + r.bearing, err(r.bearing, fox) <= 8);
    }

    @Test public void tooShallowAsksForARetry() {
        SpinBearing.Result r = SpinBearing.fit(sweep(even(0, 360, 72), h -> -85, 2, 3));
        assertFalse(r.ok); assertTrue(r.retry, r.retry.startsWith("Too little difference"));
        // a 4 dB pattern is too shallow as well
        DoubleUnaryOperator weak = h -> -85 + 2 * Math.cos(Math.toRadians(h - 90));
        assertFalse(SpinBearing.fit(sweep(even(0, 360, 72), weak, 1, 4)).ok);
    }

    @Test public void cleanerSweepsGetATighterSigma() {
        SpinBearing.Result clean = SpinBearing.fit(sweep(even(0, 360, 72), cardioid(45), 0.5, 5)), noisy = SpinBearing.fit(sweep(even(0, 360, 72), cardioid(45), 3, 5));
        assertTrue(clean.ok && noisy.ok);
        assertTrue(clean.sigma + " vs " + noisy.sigma, clean.sigma < noisy.sigma);
    }

    @Test public void guidanceAndProgress() {
        SpinBearing s = new SpinBearing();
        long t = 0; double h = 0;
        for (int i = 0; i < 8; i++) { s.heading(t, h); t += 250; h += 30; }     // 120 degrees a second
        assertEquals("Slower", s.guide());
        s = new SpinBearing(); t = 0; h = 350;
        for (int i = 0; i < 200 && !s.complete(); i++) {                          // 20 degrees a second, across north, readings at 2 Hz
            s.heading(t, (h % 360 + 360) % 360);
            if (i % 2 == 0) s.add(t, (h % 360 + 360) % 360, -80);
            if (i == 20) { assertEquals("Keep going", s.guide()); assertTrue(s.covered() > 0.2 && s.covered() < 0.6); }
            t += 250; h += 5;
        }
        assertTrue(s.complete()); assertTrue(s.turned() >= 340); assertEquals("Full circle: done", s.guide());
        SpinBearing still = new SpinBearing();
        for (int i = 0; i < 12; i++) still.heading(i * 250L, 90);
        assertEquals("Keep turning", still.guide());
    }
}
