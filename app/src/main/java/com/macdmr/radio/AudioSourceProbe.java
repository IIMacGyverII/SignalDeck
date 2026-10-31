/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;
// i18n: stays English (audio path diagnostics: the results go to the log, the Diagnostics page and the device report)

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.util.Locale;

/**
 * Does the radio's receive audio reach Android's recording API? On phones whose module drives the speaker through
 * hardware switches (the ANYSECU W6), it is the open question that decides recording, captions and every decoder.
 * Records a short stretch from each source an app may open and measures the overall level and the level of a test tone
 * (a 1 kHz tone sent to the radio, e.g. by a HackRF). Receive only; nothing is written to the radio.
 */
public final class AudioSourceProbe {
    private AudioSourceProbe() { }

    static final int RATE = 8000;
    static final int[] SOURCES = { MediaRecorder.AudioSource.DEFAULT, MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.UNPROCESSED, 10 /* VOICE_PERFORMANCE */ };
    static final String[] NAMES = { "DEFAULT", "MIC", "CAMCORDER", "VOICE_RECOGNITION", "VOICE_COMMUNICATION", "UNPROCESSED", "VOICE_PERFORMANCE" };

    /** RMS of 16-bit samples in dB relative to full scale; -120 for silence. */
    static double rmsDbfs(short[] s, int n) {
        double sum = 0; for (int i = 0; i < n; i++) sum += (double) s[i] * s[i];
        double rms = n == 0 ? 0 : Math.sqrt(sum / n);
        return rms <= 0 ? -120 : 20 * Math.log10(rms / 32768.0);
    }

    /** The level of one frequency (Goertzel), as the RMS of that sine in dB relative to full scale; -120 for none. */
    static double toneDbfs(short[] s, int n, double hz, int rate) {
        if (n == 0) return -120;
        double w = 2 * Math.PI * hz / rate, coeff = 2 * Math.cos(w), q1 = 0, q2 = 0;
        for (int i = 0; i < n; i++) { double q0 = coeff * q1 - q2 + s[i]; q2 = q1; q1 = q0; }
        double power = q1 * q1 + q2 * q2 - coeff * q1 * q2;
        double amp = 2 * Math.sqrt(Math.max(0, power)) / n;          // peak amplitude of the sine
        double rms = amp / Math.sqrt(2);
        return rms <= 0 ? -120 : 20 * Math.log10(rms / 32768.0);
    }

    /** One line per source: overall level, the tone's level and how far the tone stands above everything else. */
    public static String run(int ms, double toneHz) {
        StringBuilder r = new StringBuilder();
        int n = RATE * Math.max(500, Math.min(10_000, ms)) / 1000;
        for (int k = 0; k < SOURCES.length; k++) {
            AudioRecord rec = null;
            try {
                int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                rec = new AudioRecord(SOURCES[k], RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 4096));
                if (rec.getState() != AudioRecord.STATE_INITIALIZED) { r.append(String.format(Locale.US, "%-20s not available\n", NAMES[k])); continue; }
                short[] buf = new short[n]; int got = 0;
                rec.startRecording();
                short[] skip = new short[RATE / 5]; rec.read(skip, 0, skip.length);                 // the first 200 ms ramp up
                while (got < n) { int g = rec.read(buf, got, n - got); if (g <= 0) break; got += g; }
                double all = rmsDbfs(buf, got), tone = toneDbfs(buf, got, toneHz, RATE);
                r.append(String.format(Locale.US, "%-20s %5d samples  level %6.1f dBFS  %4.0f Hz %6.1f dBFS  (tone %+5.1f dB against the rest)\n",
                        NAMES[k], got, all, toneHz, tone, tone - all));
            } catch (Throwable t) {
                r.append(String.format(Locale.US, "%-20s failed: %s\n", NAMES[k], t.getMessage()));
            } finally {
                if (rec != null) try { rec.stop(); } catch (Throwable ignored) { } finally { rec.release(); }
            }
        }
        return r.toString();
    }
}
