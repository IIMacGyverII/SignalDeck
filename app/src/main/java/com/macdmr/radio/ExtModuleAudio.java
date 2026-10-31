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
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * The Armor 3WT's radio audio through its vendor daemon's audio socket (127.0.0.1:9985, {@link ExtModuleLink}), as its
 * intercom app moves it (phonedmrapp docs/armor3wt/stock-app-review.md §6): 10 ms frames of stereo 16-bit PCM at 48 kHz,
 * or 32 kHz when {@code ro.agold.extmodule.32k} is set, each behind the daemon's 12-byte header. Control words on the same
 * socket: 3 / 4 open and close the receive stream (the stock app sends them at each reception's start and end, which
 * {@link #reception} follows), 1 / 2 switch transmit audio on and off. The service sees the same 8 kHz stereo blocks as
 * from {@link TinyServiceAudio}; the rate change is {@link RateConverter}'s.
 */
public final class ExtModuleAudio implements AudioBackend {
    private static final String TAG = "ExtModuleAudio";
    static final int BLOCK = 2048;                                     // bytes per 8 kHz block handed to the sink, as TinyService delivers them

    final int rate, factor, frameBytes;
    private volatile Sink sink;
    private volatile Gate gate;
    private AudioTrack track;
    private volatile boolean playing, rxWanted, rxStreamOn;
    private Socket socket; private OutputStream out; private Thread reader;
    private final Object writeLock = new Object();
    private volatile long rxFrames, rxBytes;
    private Thread txThread; private volatile boolean txRunning;
    private AudioRecord rec;

    public ExtModuleAudio(int rate) {
        this.rate = rate == 32000 ? 32000 : 48000;
        this.factor = this.rate / 8000;
        this.frameBytes = ExtModuleLink.frameBytes(this.rate);
    }

    /** Frames and payload bytes received on the audio socket so far (the device report). */
    public long rxFrames() { return rxFrames; }
    public long rxBytes() { return rxBytes; }

    @Override public void setSink(Sink s) { sink = s; }
    @Override public void setGate(Gate g) { gate = g; }

    private synchronized void connect() throws IOException {
        if (socket != null && !socket.isClosed()) return;
        final Socket s = ExtModuleLink.connect(ExtModuleLink.AUDIO_PORT, 3000);
        socket = s; out = s.getOutputStream();
        final RateConverter rc = new RateConverter(factor, rate);
        reader = new Thread(() -> {
            byte[] chunk = new byte[8192]; byte[] low = new byte[ExtModuleLink.MAX_PAYLOAD / factor + 8];
            byte[] block = new byte[BLOCK]; byte[] zero = new byte[BLOCK]; final int[] fill = { 0 };
            ExtModuleLink.Reader r = new ExtModuleLink.Reader();
            try (InputStream in = s.getInputStream()) {
                while (!s.isClosed()) {
                    int n = in.read(chunk);
                    if (n < 0) break;
                    r.feed(chunk, 0, n, (type, p, off, len) -> {
                        if (type != ExtModuleLink.TYPE_DATA || len < 4) return;
                        rxFrames++; rxBytes += len;
                        byte[] frame = off == 0 ? p : java.util.Arrays.copyOfRange(p, off, off + len);
                        int k = rc.decimate(frame, len, low);
                        for (int i = 0; i < k; ) {                      // regroup into the service's 2048-byte blocks
                            int take = Math.min(k - i, BLOCK - fill[0]);
                            System.arraycopy(low, i, block, fill[0], take); fill[0] += take; i += take;
                            if (fill[0] == BLOCK) {
                                fill[0] = 0;
                                Sink sk = sink; if (sk != null) sk.onPcm(block, BLOCK);
                                Gate g = gate;
                                if (playing && track != null) track.write(g == null || g.open() ? block : zero, 0, BLOCK, AudioTrack.WRITE_NON_BLOCKING);
                            }
                        }
                    });
                }
            } catch (Throwable t) { if (!s.isClosed()) Log.w(TAG, "audio socket: " + t); }
        }, "extmodule-audio-rx");
        reader.setDaemon(true);
        reader.start();
    }

    private void control(int cmd) throws IOException { send(ExtModuleLink.control(cmd)); }
    private void send(byte[] m) throws IOException {
        OutputStream o = out; if (o == null) throw new IOException("audio socket closed");
        synchronized (writeLock) { o.write(m); o.flush(); }
    }

    @Override public synchronized String startRx(boolean speaker) {
        try {
            connect();
            if (track == null) {
                int min = AudioTrack.getMinBufferSize(8000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                track = new AudioTrack(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                        new AudioFormat.Builder().setSampleRate(8000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                        Math.max(min * 2, 8192), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
                track.play();
            }
            playing = speaker; rxWanted = true;
            return "extmodule audio socket " + ExtModuleLink.AUDIO_PORT + " open (" + rate / 1000 + " kHz, " + frameBytes + "-byte frames); the receive stream opens at each reception";
        } catch (Throwable t) { return "startRx failed: " + t; }
    }

    /**
     * A reception started or ended (the module's 0x04 push, status 1 / 2): open or close the daemon's receive stream, as the
     * stock app does (audio control 3 about 100 ms after the start, 4 at the end).
     */
    public void reception(boolean on) {
        if (!rxWanted || on == rxStreamOn) return;
        try { control(on ? ExtModuleLink.AUDIO_RX_ON : ExtModuleLink.AUDIO_RX_OFF); rxStreamOn = on; }
        catch (IOException e) { Log.w(TAG, "receive stream " + (on ? "on" : "off") + ": " + e); }
    }

    /** Bench (the device report): open or close the daemon's receive stream whether or not a reception was reported. */
    public String bench(boolean on) {
        try {
            connect();
            control(on ? ExtModuleLink.AUDIO_RX_ON : ExtModuleLink.AUDIO_RX_OFF); rxStreamOn = on;
            return "extmodule receive stream " + (on ? "opened" : "closed") + " (" + rxFrames + " frames, " + rxBytes + " bytes so far)";
        } catch (IOException e) { return "extmodule receive stream: " + e.getMessage(); }
    }

    @Override public synchronized String stopRx() {
        reception(false);
        rxWanted = false; playing = false;
        return "extmodule receive stream closed";
    }

    private volatile int txSource = MediaRecorder.AudioSource.MIC;
    @Override public void setTxSource(int source) { txSource = source; }
    private volatile TxFeed txFeed;
    @Override public void setTxFeed(TxFeed f) { txFeed = f; }
    private volatile Level txLevel;
    @Override public void setTxLevel(Level l) { txLevel = l; }

    /** Transmit audio on (control 1), then the microphone (or the web feed) in 10 ms frames at the daemon's rate. */
    @Override public synchronized String startTx() {
        try {
            if (txRunning) return "TX audio already running";
            connect();
            final TxFeed feed = txFeed;
            if (feed == null) {
                int min = AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                rec = new AudioRecord(txSource, 8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 4096));
                rec.startRecording();
            }
            control(ExtModuleLink.AUDIO_TX_ON);
            txRunning = true;
            final AudioRecord r = rec;
            final RateConverter rc = new RateConverter(factor, rate);
            txThread = new Thread(() -> {
                byte[] mono = new byte[160]; byte[] st = new byte[320]; byte[] hi = new byte[frameBytes];   // 10 ms
                try {
                    while (txRunning) {
                        int n = feed != null ? feed.read(mono, 0, mono.length) : r.read(mono, 0, mono.length);
                        if (n <= 0) continue;
                        int o = AlsaDirectAudio.stereo(mono, n, st);
                        Level lv = txLevel; if (lv != null) lv.peak(peak(mono, n));
                        int k = rc.interpolate(st, o, hi);
                        send(ExtModuleLink.wrap(ExtModuleLink.TYPE_DATA, hi, 0, k));
                    }
                } catch (Throwable t) { Log.w(TAG, "TX audio: " + t); }
            }, "extmodule-audio-tx");
            txThread.start();
            return "TX audio started (extmodule audio socket, " + rate / 1000 + " kHz)";
        } catch (Throwable t) { txRunning = false; return "startTx failed: " + t; }
    }

    @Override public synchronized String stopTx() {
        txRunning = false;
        try { if (txThread != null) txThread.join(500); } catch (InterruptedException ignored) { }
        try { if (rec != null) { rec.stop(); rec.release(); rec = null; } } catch (Throwable ignored) { }
        try { control(ExtModuleLink.AUDIO_TX_OFF); } catch (IOException e) { return "TX stopped (audio off not sent: " + e.getMessage() + ")"; }
        return "TX stopped";
    }

    private volatile boolean abortPlay;
    @Override public void abortPlay() { abortPlay = true; }
    /** 8 kHz mono PCM to the transmitter in 10 ms frames, paced to real time; transmit audio on around it. */
    @Override public String playPcm(short[] mono) {
        abortPlay = false;
        try {
            connect();
            control(ExtModuleLink.AUDIO_TX_ON);
            RateConverter rc = new RateConverter(factor, rate);
            byte[] st = new byte[320]; byte[] hi = new byte[frameBytes];
            long t0 = System.nanoTime(); int frames = 0;
            for (int pos = 0; pos < mono.length && !abortPlay; pos += 80) {
                int k = 0;
                for (int i = pos; i < Math.min(mono.length, pos + 80); i++) { short v = mono[i]; st[k++] = (byte) v; st[k++] = (byte) (v >> 8); st[k++] = (byte) v; st[k++] = (byte) (v >> 8); }
                send(ExtModuleLink.wrap(ExtModuleLink.TYPE_DATA, hi, 0, rc.interpolate(st, k, hi)));
                long ahead = t0 + (++frames) * 10_000_000L - System.nanoTime() - 20_000_000L;   // keep about two frames queued
                if (ahead > 0) Thread.sleep(ahead / 1_000_000L, (int) (ahead % 1_000_000L));
            }
            control(ExtModuleLink.AUDIO_TX_OFF);
            return (abortPlay ? "aborted after part of " : "played ") + mono.length + " samples";
        } catch (Throwable t) { return "playPcm: " + t; }
    }

    /** The radio is let go (Exit or Hand back): close the audio socket, so the daemon has one client again. */
    @Override public void releaseForStockApp() { close(); }

    /** Close the audio socket. */
    public synchronized void close() {
        txRunning = false; rxWanted = false; playing = false; rxStreamOn = false;
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
        socket = null; out = null;
        if (track != null) { try { track.release(); } catch (Throwable ignored) { } track = null; }
    }

    private static int peak(byte[] mono, int n) {
        int pk = 0; for (int i = 0; i + 1 < n; i += 2) pk = Math.max(pk, Math.abs((short) ((mono[i] & 0xFF) | (mono[i + 1] << 8))));
        return pk;
    }
}
