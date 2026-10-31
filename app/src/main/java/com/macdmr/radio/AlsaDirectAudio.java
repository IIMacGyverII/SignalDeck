/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;
// i18n: stays English (audio path diagnostics: the results go to the log, the Diagnostics page and the device report)

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Voice audio straight from the phone's sound card, for a ROM that lacks the vendor audio service (a custom ROM or a GSI
 * on the Ulefone Armor 26 Ultra: no android.os.PrizeTinyService, no tinyserver). The kernel's sound driver and the
 * phone's codec are still there, so SignalDeck's root helper sdalsa (src/main/cpp/sdalsa, shipped as an asset) sets the
 * profile's mixer controls and moves 8 kHz stereo 16-bit PCM over a pipe: capture to stdout for receive, stdin to
 * playback for transmit. Frames have the same format as {@link TinyServiceAudio}'s, so the service sees no difference.
 */
public final class AlsaDirectAudio implements AudioBackend {
    private static final String TAG = "AlsaDirectAudio";
    static final int BLOCK = 2048;                                     // bytes per receive block, as TinyService delivers them

    private final DeviceProfile.DirectAudio da;
    private final String helper;
    private volatile Sink sink;
    private volatile Gate gate;
    private AudioTrack track;
    private volatile boolean playing;
    private Process rxProc; private Thread rxThread; private volatile boolean rxRunning;
    // one flag per capture session: a stopRx() then startRx() must not leave the last session's threads looping on the shared rxRunning
    private volatile java.util.concurrent.atomic.AtomicBoolean rxLive = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile long rxBlocks, rxDropped;   // rxDropped: USB-card blocks the processing thread could not take in time
    private Process txProc; private Thread txThread; private volatile boolean txRunning;
    private AudioRecord rec;
    private final StringBuilder err = new StringBuilder();

    public AlsaDirectAudio(Context c, DeviceProfile.DirectAudio da) throws Exception {
        this.da = da;
        this.helper = install(c);
    }

    /** Which audio path a vendor-service phone uses: "vendor" (PrizeTinyService) or "direct" (this class). */
    static String choose(String pref, boolean classPresent, boolean haveRecipe) {
        if (!haveRecipe) return "vendor";
        if ("direct".equals(pref)) return "direct";
        if ("vendor".equals(pref)) return "vendor";
        return classPresent ? "vendor" : "direct";
    }

    /** The helper for this phone's ABI, copied from the APK's assets into files/bin (again whenever it changed). */
    static String install(Context c) throws Exception { return install(c, "sdalsa"); }

    /** A native helper shipped as assets/NAME/ABI/NAME (sdalsa, the report's sdtrace), installed the same way. */
    static String install(Context c, String name) throws Exception {
        String abi = null;
        for (String a : Build.SUPPORTED_ABIS) if (a.equals("arm64-v8a") || a.equals("armeabi-v7a")) { abi = a; break; }
        if (abi == null) throw new IllegalStateException("no " + name + " helper for " + Arrays.toString(Build.SUPPORTED_ABIS));
        byte[] want;
        try (InputStream in = c.getAssets().open(name + "/" + abi + "/" + name)) { want = readAll(in); }
        File dir = new File(c.getFilesDir(), "bin"); if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("cannot create " + dir);
        File f = new File(dir, name);
        boolean same = false;
        if (f.length() == want.length) try (InputStream in = new java.io.FileInputStream(f)) { same = Arrays.equals(readAll(in), want); }
        if (!same) {
            File tmp = new File(dir, name + ".tmp");
            try (OutputStream o = new FileOutputStream(tmp)) { o.write(want); }
            if (!tmp.renameTo(f)) throw new IllegalStateException("cannot install " + f);
        }
        if (!f.setExecutable(true, true)) throw new IllegalStateException("cannot make " + f + " executable");
        return f.getAbsolutePath();
    }
    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    /** The root command line for one stream: the controls before "--" are set before it opens, the others after it ends. */
    static String command(String helper, boolean capture, DeviceProfile.DirectAudio d) {
        StringBuilder b = new StringBuilder("exec ").append(quote(helper)).append(capture ? " cap" : " play")
                .append(" -c ").append(d.card).append(" -d ").append(capture ? d.captureDevice : d.playbackDevice)
                .append(" -r ").append(d.rate).append(" -n 2 -p ").append(capture && d.capturePeriod > 0 ? d.capturePeriod : 256 * (d.rate / 8000)).append(" -k ").append(capture && d.capturePeriods > 0 ? d.capturePeriods : d.rate > 8000 ? 16 : 4).append(capture && d.rate > 8000 ? " -q 1" : "");   // a USB card (Atom XL) gets 512 ms: one overrun stops its stream until the bridge is power-cycled
        for (String c : capture ? d.rxOn : d.txOn) b.append(' ').append(quote(c));
        b.append(" --");
        for (String c : capture ? d.rxOff : d.txOff) b.append(' ').append(quote(c));
        return b.toString();
    }
    static String quote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

    private Process start(boolean capture) throws java.io.IOException {
        // --mount-master: an app's su otherwise runs in the app's own mount namespace; there the Atom XL's USB card capture
        // failed with an I/O error while the same helper from a root shell ran for minutes (2026-10-28)
        // only for the USB sound cards (rate above 8 kHz: the Atom XL); the 8 kHz built-in card path keeps its proven plain su
        Process p = (da.rate > 8000 ? new ProcessBuilder("su", "--mount-master", "--context", "u:r:magisk:s0", "-c", command(helper, capture, da))
                                    : new ProcessBuilder("su", "-c", command(helper, capture, da))).start();
        final InputStream es = p.getErrorStream();
        Thread t = new Thread(() -> {                                    // the helper's own messages: busy device, a missing control, xruns
            try (BufferedReader r = new BufferedReader(new InputStreamReader(es))) {
                String line; while ((line = r.readLine()) != null) { Log.i(TAG, line); synchronized (err) { if (err.length() < 2000) err.append(line).append('\n'); } }
            } catch (Throwable ignored) { }
        }, capture ? "sdalsa-cap-err" : "sdalsa-play-err");
        t.setDaemon(true); t.start();
        return p;
    }
    private String takeErrors() { synchronized (err) { String e = err.toString().trim(); err.setLength(0); return e; } }

    private static void finish(Process p, long ms) {
        if (p == null) return;
        try { if (!p.waitFor(ms, TimeUnit.MILLISECONDS)) p.destroy(); } catch (InterruptedException e) { p.destroy(); }
    }

    /** Called (on the reader thread) when the capture helper ends while capture should still be running. */
    private volatile java.util.function.Consumer<String> onCaptureEnded;
    void setOnCaptureEnded(java.util.function.Consumer<String> c) { onCaptureEnded = c; }

    @Override public void setSink(Sink s) { sink = s; }
    @Override public void setGate(Gate g) { gate = g; }

    /** Start RX: the helper captures, this side plays the blocks through an AudioTrack (through the gate). */
    @Override public synchronized String startRx(boolean speaker) {
        try {
            if (capturing()) return "capture already running";
            rxProc = null;                                                   // a helper that ended by itself is not in the way
            if (track == null) {
                int min = AudioTrack.getMinBufferSize(8000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                track = new AudioTrack(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                        new AudioFormat.Builder().setSampleRate(8000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                        Math.max(min * 2, 8192), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
                track.play();
            }
            takeErrors();
            final Process p = start(true); rxProc = p; rxRunning = true; playing = true;
            final java.util.concurrent.atomic.AtomicBoolean live = new java.util.concurrent.atomic.AtomicBoolean(true); rxLive = live;
            final long before = rxBlocks;
            final int f = da.rate / 8000;                                // a faster card (the Atom XL's USB card, 32 kHz) is brought down to 8 kHz here
            final RateConverter rc = f > 1 ? new RateConverter(f, da.rate) : null;
            // a USB card (f > 1, the Atom XL): the reader only reads; conversion, the sink (decoders) and the speaker run on their
            // own thread behind a bounded queue that drops when full. A stalled reader lets the card overrun, and one overrun stops
            // the Atom XL bridge's stream until it is power-cycled (2026-10-28). The 8 kHz codec path (Armor) is unchanged.
            final java.util.concurrent.ArrayBlockingQueue<byte[]> q = f > 1 ? new java.util.concurrent.ArrayBlockingQueue<>(32) : null;
            if (q != null) {
                Thread proc = new Thread(() -> {
                    byte[] buf = new byte[BLOCK]; byte[] zero = new byte[BLOCK];
                    try {
                        while (live.get() || !q.isEmpty()) {
                            byte[] raw = q.poll(200, TimeUnit.MILLISECONDS); if (raw == null) continue;
                            rc.decimate(raw, raw.length, buf);
                            Sink s = sink; if (s != null) s.onPcm(buf, BLOCK);
                            Gate g = gate;
                            if (playing && track != null) track.write(g == null || g.open() ? buf : zero, 0, BLOCK, AudioTrack.WRITE_NON_BLOCKING);
                        }
                    } catch (Throwable t) { Log.w(TAG, "capture processing: " + t); }
                }, "sdalsa-rx-proc");
                proc.start();
            }
            rxThread = new Thread(() -> {
                byte[] buf = new byte[BLOCK]; byte[] zero = new byte[BLOCK]; byte[] raw = f > 1 ? new byte[BLOCK * f] : buf;
                try (InputStream in = p.getInputStream()) {
                    while (live.get()) {
                        int got = 0;
                        while (got < raw.length) { int n = in.read(raw, got, raw.length - got); if (n < 0) break; got += n; }
                        if (got < raw.length) break;                     // the helper ended
                        rxBlocks++;
                        if (q != null) { if (!q.offer(raw)) rxDropped++; raw = new byte[BLOCK * f]; continue; }
                        Sink s = sink; if (s != null) s.onPcm(buf, BLOCK);  // squelch decision first
                        Gate g = gate;
                        if (playing && track != null) track.write(g == null || g.open() ? buf : zero, 0, BLOCK);   // keep the stream timing, play silence
                    }
                } catch (Throwable t) { Log.w(TAG, "capture: " + t); }
                java.util.function.Consumer<String> ended = onCaptureEnded;
                if (rxRunning && rxProc == p && ended != null) { try { p.waitFor(300, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { } ended.accept(takeErrors()); }
            }, "sdalsa-rx");                                             // leaving closes the pipe: the helper's next write fails and it puts the controls back
            rxThread.start();
            long t0 = System.currentTimeMillis();                        // the first block, or the helper's refusal (busy device, missing control)
            // the Atom XL's USB card delivers its first block in 105–170 ms, and an open that stays empty only recovers with a
            // bridge power-cycle (2026-10-29): give up sooner there, so a missed open costs the next reception less
            final long firstBlockMs = f > 1 ? 800 : 2000;
            while (rxBlocks == before && System.currentTimeMillis() - t0 < firstBlockMs) {
                if (!p.isAlive()) { rxProc = null; rxRunning = false; live.set(false); return "startRx failed: sdalsa capture ended (exit " + p.exitValue() + ") " + takeErrors(); }
                Thread.sleep(20);
            }
            return rxBlocks == before ? "sdalsa capture started, no audio block yet " + takeErrors() : "sdalsa capture on device " + da.captureDevice;
        } catch (Throwable t) { rxProc = null; rxRunning = false; rxLive.set(false); return "startRx failed: " + t; }
    }

    /** True while the capture helper runs. */
    /** True while the capture helper is still running: one that ended on its own (EIO) keeps its Process object, and counted
     *  as running it was kept for the next reception (no sound) and cancelled the bridge restart that would have fixed it (2026-10-29). */
    boolean capturing() { Process p = rxProc; return p != null && p.isAlive(); }

    @Override public synchronized String stopRx() {
        playing = false; rxRunning = false; rxLive.set(false);
        Process p = rxProc; rxProc = null;
        if (p == null) return "capture not running";
        try { if (rxThread != null) rxThread.join(500); } catch (InterruptedException ignored) { }
        finish(p, 1500);
        String e = takeErrors();
        long dropped = rxDropped;
        return "sdalsa capture stopped" + (dropped > 0 ? " (" + dropped + " blocks dropped so far: the processing fell behind)" : "") + (e.isEmpty() ? "" : ": " + e);
    }

    private volatile int txSource = MediaRecorder.AudioSource.MIC;
    @Override public void setTxSource(int source) { txSource = source; }
    private volatile TxFeed txFeed;
    @Override public void setTxFeed(TxFeed f) { txFeed = f; }
    private volatile Level txLevel;
    @Override public void setTxLevel(Level l) { txLevel = l; }

    /** TX: phone mic (or the web feed) → 8 kHz mono → stereo-duplicated → the helper's playback while keyed. */
    @Override public synchronized String startTx() {
        try {
            if (txProc != null) return "playback already running";
            takeErrors();
            final TxFeed feed = txFeed;
            if (feed == null) {
                int min = AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                rec = new AudioRecord(txSource, 8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 4096));
                rec.startRecording();
            }
            final Process p = start(false); txProc = p; txRunning = true;
            final AudioRecord r = rec;
            final int f = da.rate / 8000;
            final RateConverter rc = f > 1 ? new RateConverter(f, da.rate) : null;
            txThread = new Thread(() -> {
                byte[] mono = new byte[BLOCK / 2]; byte[] st = new byte[BLOCK]; byte[] hi = new byte[BLOCK * f];
                try (OutputStream out = p.getOutputStream()) {
                    while (txRunning) {
                        int n = feed != null ? feed.read(mono, 0, mono.length) : r.read(mono, 0, mono.length);
                        if (n <= 0) continue;
                        int o = stereo(mono, n, st);
                        Level lv = txLevel; if (lv != null) lv.peak(peak(mono, n));
                        if (rc != null) out.write(hi, 0, rc.interpolate(st, o, hi)); else out.write(st, 0, o);
                    }
                } catch (Throwable t) { synchronized (err) { err.append(t).append('\n'); } }
            }, "sdalsa-tx");                                             // closing stdin ends the helper: it drains, then puts the controls back
            txThread.start();
            return "TX started (sdalsa playback on device " + da.playbackDevice + ")";
        } catch (Throwable t) { return "startTx failed: " + t; }
    }

    @Override public synchronized String stopTx() {
        txRunning = false;
        try { if (txThread != null) txThread.join(500); } catch (InterruptedException ignored) { }
        try { if (rec != null) { rec.stop(); rec.release(); rec = null; } } catch (Throwable ignored) { }
        Process p = txProc; txProc = null;
        finish(p, 1500);
        String e = takeErrors();
        return "TX stopped" + (e.isEmpty() ? "" : " errors: " + e);
    }

    /** Play 8 kHz mono PCM into the TX path; blocks until the helper has played it out. */
    private volatile boolean abortPlay;
    @Override public void abortPlay() { abortPlay = true; }
    @Override public String playPcm(short[] mono) {
        Process p;
        abortPlay = false;
        synchronized (this) { takeErrors(); try { p = start(false); } catch (Throwable t) { return "playPcm: " + t; } }
        final int f = da.rate / 8000;
        final RateConverter rc = f > 1 ? new RateConverter(f, da.rate) : null;
        try (OutputStream out = p.getOutputStream()) {
            byte[] st = new byte[BLOCK]; byte[] hi = new byte[BLOCK * f];
            for (int pos = 0; pos < mono.length && !abortPlay; pos += BLOCK / 4) {
                int k = 0;
                for (int i = pos; i < Math.min(mono.length, pos + BLOCK / 4); i++) { short v = mono[i]; st[k++] = (byte) v; st[k++] = (byte) (v >> 8); st[k++] = (byte) v; st[k++] = (byte) (v >> 8); }
                if (rc != null) out.write(hi, 0, rc.interpolate(st, k, hi)); else out.write(st, 0, k);   // the pipe fills and the helper's playback paces it to real time
            }
        } catch (Throwable t) { finish(p, 500); return "playPcm: " + t; }
        finish(p, abortPlay ? 500 : 3000);                               // end of input: the helper drains the last periods, then exits
        String e = takeErrors();
        return (abortPlay ? "aborted after part of " : "played ") + mono.length + " samples" + (e.isEmpty() ? "" : " (" + e + ")");
    }

    /** 8 kHz mono little-endian bytes to stereo-duplicated frames; returns the stereo byte count. */
    static int stereo(byte[] mono, int n, byte[] st) {
        int o = 0;
        for (int i = 0; i + 1 < n && o + 3 < st.length; i += 2) { st[o++] = mono[i]; st[o++] = mono[i + 1]; st[o++] = mono[i]; st[o++] = mono[i + 1]; }
        return o;
    }
    private static int peak(byte[] mono, int n) {
        int pk = 0; for (int i = 0; i + 1 < n; i += 2) pk = Math.max(pk, Math.abs((short) ((mono[i] & 0xFF) | (mono[i + 1] << 8))));
        return pk;
    }
}
