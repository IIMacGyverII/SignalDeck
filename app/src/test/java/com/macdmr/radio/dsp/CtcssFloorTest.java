/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.dsp;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Random;

public class CtcssFloorTest {
    /** 8 kHz mono 16-bit LE: a tone at {@code toneRms} (0 = none) plus white noise at {@code noiseRms}. */
    private static byte[] audio(double seconds, double hz, double toneRms, double noiseRms, long seed) {
        int n = (int) (8000 * seconds); byte[] b = new byte[2 * n]; Random r = new Random(seed);
        for (int i = 0; i < n; i++) {
            int v = (int) Math.round(toneRms * Math.sqrt(2) * Math.sin(2 * Math.PI * hz * i / 8000.0) + noiseRms * r.nextGaussian());
            b[2 * i] = (byte) v; b[2 * i + 1] = (byte) (v >> 8);
        }
        return b;
    }

    @Test public void aLoneTonOnAQuietInputNeedsItsFloor() {
        byte[] x = audio(4, 100.0, 24, 3, 1);                           // the W6Plus DMR: a carrier with only 100.0 Hz, RMS ~24
        CtcssDecoderBank def = new CtcssDecoderBank(8000); def.processMono(x, x.length);
        assertTrue("the Armor's floor ignores it", def.detectedHz() < 0);
        CtcssDecoderBank low = new CtcssDecoderBank(8000); low.setEnergyMin(200); low.processMono(x, x.length);
        assertEquals(100.0, low.detectedHz(), 0.05);
    }

    @Test public void quietNoiseStaysOutWithTheLowFloor() {
        byte[] x = audio(4, 0, 0, 24, 2);                               // noise as loud as that tone, no tone
        CtcssDecoderBank low = new CtcssDecoderBank(8000); low.setEnergyMin(200); low.processMono(x, x.length);
        assertTrue(low.detectedHz() < 0);
    }
}
