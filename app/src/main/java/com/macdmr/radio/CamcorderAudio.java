/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;

/**
 * Receive audio on phones whose module drives the speaker through hardware switches and whose audio HAL routes that
 * same audio to the camcorder recording input while the radio receives (the ANYSECU W6+, measured 2026-10-27 with a
 * HackRF tone: docs/w6/RX_AUDIO_TEST.md). The audio goes to SignalDeck's squelch, recorder, captions and decoders as
 * 8 kHz stereo frames with L == R like the other backends. With {@code play} (the profile's appPlayback) it is also
 * played here, through the gate, on Android's media stream, and the module's own speaker route stays off; without it
 * the speaker is the module's own path. Voice goes from the microphone to the module directly (its lines); audio SignalDeck
 * makes itself (APRS, tones, pictures) goes through the profile's gateway lines when it has them (the W6Plus DMR), else nowhere.
 */
public final class CamcorderAudio implements AudioBackend {
    static final int RATE = 8000, BLOCK = 256;          // 32 ms blocks
    /**
     * The camcorder input carries the radio about 21 dB lower than the other phones' audio (W6, 2026-10-27: a 1 kHz tone
     * at 2.5 kHz deviation arrived at -44 dBFS with the module volume at 4 of 8, and the SSTV decoder skipped a Robot 36
     * picture as "likely noise"). A fixed gain of 12 brings it to about -22 dBFS; samples are clipped, never wrapped.
     * The default; a profile with a louder module sets its own ({@link DeviceProfile#camcorderGain}).
     */
    static final int GAIN = 12;
    private volatile Sink sink;
    private volatile Gate gate;
    private volatile boolean running;
    private Thread thread;
    private AudioRecord rec;
    private AudioTrack track;
    private final boolean play;
    private final int gain;

    public CamcorderAudio() { this(false); }
    public CamcorderAudio(boolean play) { this(play, GAIN); }
    public CamcorderAudio(boolean play, int gain) { this(play, gain, null, null, 1f, null, 0.4f); }
    public CamcorderAudio(boolean play, int gain, String[][] gatewayOn, String[][] gatewayOff, float txScale, AudioManager am, float gatewayVolume) {
        this.play = play; this.gain = Math.max(1, gain); this.gatewayOn = gatewayOn; this.gatewayOff = gatewayOff; this.txScale = txScale; this.am = am; this.gatewayVolume = gatewayVolume;
    }
    private final AudioManager am;
    private final float gatewayVolume;
    private volatile float txScale;
    String setTxScale(float v) { txScale = Math.max(0.001f, Math.min(1f, v)); return "gateway sample factor " + txScale; }
    private final String[][] gatewayOn, gatewayOff;
    private RootShell lineShell;

    @Override public void setSink(Sink s) { sink = s; }
    @Override public void setGate(Gate g) { gate = g; }   // only used when this backend plays the audio itself
    @Override public boolean appPlaysRadio() { return play; }   // app playback: the gate holds the radio's audio back; otherwise the module drives the speaker

    /** Mono 16-bit samples, times {@link #GAIN} and clipped, to the stereo (L == R) little-endian frames the sink takes. */
    static int toStereo(short[] mono, int n, byte[] out) { return toStereo(mono, n, out, GAIN); }
    static int toStereo(short[] mono, int n, byte[] out, int gain) {
        for (int i = 0; i < n; i++) {
            int v = Math.max(-32768, Math.min(32767, mono[i] * gain));
            byte lo = (byte) v, hi = (byte) (v >> 8);
            out[4 * i] = lo; out[4 * i + 1] = hi; out[4 * i + 2] = lo; out[4 * i + 3] = hi;
        }
        return 4 * n;
    }

    @Override public synchronized String startRx(boolean speaker) {
        if (running) return "receive audio already running (camcorder input)";
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.CAMCORDER, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 8 * BLOCK * 2));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) { rec.release(); rec = null; return "startRx failed: the camcorder input could not be opened"; }
            rec.startRecording();
            if (play) {
                int tmin = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                track = new AudioTrack(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                        new AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                        Math.max(tmin, 4 * BLOCK * 2), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
                track.play();
            }
        } catch (Throwable t) { return "startRx failed: " + t.getMessage(); }
        running = true;
        final AudioRecord r = rec; final AudioTrack t = track;
        thread = new Thread(() -> {
            short[] mono = new short[BLOCK], out = new short[BLOCK]; byte[] st = new byte[4 * BLOCK];
            while (running) {
                int n = r.read(mono, 0, BLOCK);
                if (n <= 0) continue;
                Sink s = sink; if (s != null) s.onPcm(st, toStereo(mono, n, st, gain));
                if (t != null) {                                                   // after the sink: the gate reflects this block's squelch
                    Gate g = gate; boolean open = g == null || g.open();
                    for (int i = 0; i < n; i++) out[i] = open ? (short) Math.max(-32768, Math.min(32767, mono[i] * gain)) : 0;
                    t.write(out, 0, n);
                }
            }
        }, "camcorder-rx");
        thread.start();
        return "receive audio from the camcorder input, 8 kHz" + (play ? ", played by SignalDeck (the module's speaker route stays off)" : " (the speaker is the module's own path)");
    }

    @Override public synchronized String stopRx() {
        running = false;
        if (thread != null) try { thread.join(500); } catch (InterruptedException ignored) { }
        if (rec != null) { try { rec.stop(); } catch (Throwable ignored) { } rec.release(); rec = null; }
        if (track != null) { try { track.stop(); } catch (Throwable ignored) { } track.release(); track = null; }
        return "receive audio stopped";
    }

    private static final String NO_TX = "transmit audio is not available on this phone yet";
    @Override public String startTx() { return NO_TX; }
    @Override public String stopTx() { return NO_TX; }

    private volatile boolean abortPlay;
    @Override public void abortPlay() { abortPlay = true; }
    /**
     * With gateway lines (the W6Plus DMR): the module's microphone input switched to the phone's audio output for the
     * duration, the samples played on Android's media stream (the speaker amplifier is off), then switched back. The
     * caller has keyed the radio; this blocks until the samples have played.
     */
    @Override public String playPcm(short[] mono) {
        if (gatewayOn == null) return NO_TX;
        abortPlay = false;
        AudioTrack t = null;
        String[][] before = snapshot(gatewayOn);                               // what the gateway nodes held: put back exactly, even on an error
        int userVolume = -1;
        try {
            lines(gatewayOn);
            if (am != null) {                                                   // the samples go through the media stream: hold it at the profile's level
                userVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(1, Math.round(max * gatewayVolume)), 0);
            }
            int tmin = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            t = new AudioTrack(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                    new AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                    Math.max(tmin, 4 * BLOCK * 2), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            t.play();
            short[] out = new short[mono.length]; float k = txScale;
            for (int i = 0; i < mono.length; i++) out[i] = (short) Math.round(mono[i] * k);
            int pos = 0;
            while (pos < out.length && !abortPlay) { int n = t.write(out, pos, Math.min(BLOCK, out.length - pos)); if (n <= 0) break; pos += n; }
            long end = System.currentTimeMillis() + 2000;                       // let the buffered tail play out
            while (!abortPlay && t.getPlaybackHeadPosition() < pos && System.currentTimeMillis() < end) Thread.sleep(10);
            return "played " + pos * 1000 / RATE + " ms through the gateway route" + (abortPlay ? " (stopped)" : "");
        } catch (Throwable e) { return "playPcm: " + e; }
        finally {
            if (t != null) { try { t.stop(); } catch (Throwable ignored) { } t.release(); }
            if (userVolume >= 0) try { am.setStreamVolume(AudioManager.STREAM_MUSIC, userVolume, 0); } catch (Throwable ignored) { }
            lines(gatewayOff);
            if (before != null) lines(before);
        }
    }
    /** {node, value} of each node in {@code l} as it reads now, the relay first; null when one cannot be read. */
    private synchronized String[][] snapshot(String[][] l) {
        if (lineShell == null) lineShell = new RootShell();
        java.util.List<String[]> out = new java.util.ArrayList<>();
        for (int i = l.length - 1; i >= 0; i--) {
            String node = l[i][0]; boolean seen = false;
            for (String[] o : out) if (o[0].equals(node)) seen = true;
            if (seen) continue;
            String v = lineShell.run("cat " + node).trim();
            if (!v.matches("-?\\d+")) return null;
            out.add(new String[] { node, v });
        }
        out.sort((a, b) -> a[0].contains("relay") ? -1 : b[0].contains("relay") ? 1 : 0);
        return out.toArray(new String[0][]);
    }
    private synchronized void lines(String[][] l) {
        if (l == null || l.length == 0) return;
        if (lineShell == null) lineShell = new RootShell();
        StringBuilder c = new StringBuilder();
        for (String[] p : l) c.append("echo ").append(p[1]).append(" > ").append(p[0]).append("; ");
        lineShell.run(c.toString());
    }
}
