/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import com.macdmr.radio.i18n.Msg;
import com.macdmr.radio.i18n.Txt;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

import com.macdmr.radio.ham.Codec2;
import com.macdmr.radio.ham.HeldPath;
import com.macdmr.radio.ham.HeldPathReceiver;
import com.macdmr.radio.ham.M17;
import com.macdmr.radio.ham.M17Receiver;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * M17 receive on the current FM channel: the equalizing receiver, Codec 2 voice to the phone's speaker (the radio's own
 * audio, which would be 4FSK noise, is held back while a transmission is decoded), and a list of what was heard.
 * Receive only: this radio's transmit path cannot produce M17 that M17 radios decode.
 */
public final class M17Station {
    public interface Host { void event(String line); void heard(String call); void changed(); default void voice(short[] pcm) { } default int meter() { return -999; } default void text(String line) { event(line); } default void sms(String src, String dst, String body, boolean sent) { }
        /** {@link #event} and {@link #text} kept as messages, so the history can show them in the app's language; by default their English. */
        default void event(Msg m) { event(m.english()); }
        default void text(Msg m) { text(m.english()); } }

    /** One M17 text, sent or received. */
    public static final class Text {
        public final long ts; public final boolean sent; public final String src, dst, body;
        Text(long ts, boolean sent, String src, String dst, String body) { this.ts = ts; this.sent = sent; this.src = src; this.dst = dst; this.body = body; }
    }

    /** One transmission heard. */
    public static final class Heard {
        public final long start; public long end; public String src = "", dst = "", meta; public int frames, good; public boolean encrypted, data;
        Heard(long t) { start = t; end = t; }
    }

    private final Context ctx; private final Host host; private final String profileId;
    private volatile M17Receiver rx;                                     // swapped by the self-calibration thread
    private HeldPathReceiver held; private M17Receiver.Listener dfeEar; private HeldPathReceiver.Listener heldEar;
    private Msg dfeNote;
    private Codec2 codec;
    private AudioTrack track;
    private volatile boolean running;
    private volatile Heard current;
    private final List<Heard> heard = new ArrayList<>();
    private final List<Text> texts = new ArrayList<>();
    private volatile float quality;
    /** Bench: also append the decoded voice to cache/m17_out.raw (8 kHz 16-bit mono). */
    public static volatile boolean benchDump;
    private volatile Msg calibration;                                  // null: none yet
    private final HeldCal cal;
    private final DfeCal dfeCal;
    private float[] dfeCoef;
    /** The decision-feedback receiver's audio: the last second always, and each call from a second before its link setup, up to 10 s (the self-calibration's check). */
    private final short[] lastSecond = new short[8000]; private int lastPos;
    private short[] capture; private int captureLen;
    private double[] heldTaps;
    private boolean swapping;

    public M17Station(Context c, String profileId, Host h) { ctx = c.getApplicationContext(); this.profileId = profileId; host = h; cal = new HeldCal(ctx, profileId); dfeCal = new DfeCal(new java.io.File(ctx.getFilesDir(), "m17"), profileId); }

    public boolean running() { return running; }
    /** A transmission is being decoded (the radio's own audio is held back). */
    public boolean receiving() { return running && current != null; }
    public Heard current() { return current; }
    public synchronized List<Heard> heard() { return new ArrayList<>(heard); }
    public synchronized List<Text> texts() { return new ArrayList<>(texts); }
    public synchronized void noteSent(String src, String dst, String body) { remember(new Text(System.currentTimeMillis(), true, src, dst, body)); host.sms(src, dst, body, true); host.changed(); }
    public float quality() { return quality; }
    public String calibration() { Msg c = calibration; return c == null ? "" : c.text(); }
    /** The calibration line in English, for DIAG. */
    public String calibrationEnglish() { Msg c = calibration; return c == null ? "" : c.english(); }
    public void resetCalibration() {
        if (cal != null) cal.reset();
        dfeCal.reset();
        synchronized (this) { if (running && held == null) { float[] c = shippedTable(); if (c != null) { dfeCoef = c; rx = new M17Receiver(c, dfeEar); calibration = dfeNote; } } }
        stopHeld(); setHeldPath(true); host.changed();
    }
    /** The fixed-tap front end is in use (the module is holding its voice filter open). */
    public boolean held() { return held != null; }

    public synchronized String start() {
        if (running) return null;
        float[] coef = shippedTable();
        if (coef == null) return Txt.s(R.string.modes_m17_err_calibration);
        dfeNote = calibration;
        float[] own = dfeCal.accepted();
        if (own != null) { coef = own; calibration = Msg.of(R.string.modes_m17_cal_fitted); }
        dfeCoef = coef;
        try { codec = new Codec2(Codec2.MODE_3200); } catch (Throwable t) { return Txt.s(R.string.modes_m17_err_codec, String.valueOf(t.getMessage())); }
        dfeEar = new M17Receiver.Listener() {
            @Override public void started(M17.Lsf l) { begin(l); }
            @Override public void frame(M17.Stream s, float q) { onFrame(s, q); }
            @Override public void lsfFromLich(M17.Lsf l) { onLink(l); }
            @Override public void ended(int frames, int good, String why) { finish(frames, good, why); }
            @Override public void packet(M17.Lsf l, byte[] body) { onPacket(l, body); }
            @Override public void framesLost() { noteLost(); }
        };
        heldEar = new HeldPathReceiver.Listener() {
            @Override public void started(M17.Lsf l) { begin(l); }
            @Override public void link(M17.Lsf l) { onLink(l); }
            @Override public void frame(M17.Stream s, float q) { onFrame(s, q); }
            @Override public void ended(int frames, int good, String why) { finish(frames, good, why); }
            @Override public void packet(M17.Lsf l, byte[] body) { onPacket(l, body); }
            @Override public void framesLost() { noteLost(); }
        };
        rx = new M17Receiver(coef, dfeEar);
        running = true; host.changed();
        return null;
    }

    /**
     * Use the fixed taps for this phone instead of the decision-feedback equalizer. No taps for the profile leaves
     * the decision-feedback receiver in place. Called when the module has accepted the voice-filter hold, and again
     * with false when that hold is released.
     */
    public synchronized void setHeldPath(boolean on) {
        if (!running) { stopHeld(); return; }
        if (!on) { stopHeld(); calibration = dfeNote; host.changed(); return; }
        if (held != null) return;
        double[] taps = cal.accepted();
        if (taps == null) taps = loadTaps("m17/held_" + profileId + ".txt");
        if (taps == null) { Log.i("M17Station", "held path: no taps for " + profileId); return; }
        heldTaps = taps;
        held = new HeldPathReceiver(taps, heldEar);
        calibration = cal.lineMsg() == null ? Msg.of(R.string.modes_m17_cal_held) : cal.lineMsg();
        host.changed();
    }

    /** The fixed taps this station uses for the held path: the phone's own fit, else the shipped table. */
    public double[] taps() {
        if (heldTaps != null) return heldTaps;
        double[] t = cal != null ? cal.accepted() : null;
        if (t == null) t = loadTaps("m17/held_" + profileId + ".txt");
        return t;
    }

    public synchronized void stop() {
        running = false; current = null;
        stopHeld();
        if (track != null) { try { track.pause(); track.flush(); track.release(); } catch (Throwable ignored) { } track = null; }
        if (codec != null) { codec.close(); codec = null; }
        rx = null; host.changed();
    }

    private void stopHeld() { HeldPathReceiver h = held; held = null; if (h != null) h.stop(); }

    /** Every receive block (16-bit little-endian mono, 8 kHz), whatever the squelch. The held path locks on a sync word; the other receiver uses the preamble. */
    public void feed(byte[] mono, int n) {
        if (!running) return;
        HeldPathReceiver h = held;
        if (h != null) h.feed(mono, n);
        else { M17Receiver r = rx; if (r != null) { keepAudio(mono, n); r.feed(mono, n); } }
    }
    private void keepAudio(byte[] mono, int n) {
        for (int i = 0; i + 1 < n; i += 2) {
            short v = (short) ((mono[i] & 0xFF) | (mono[i + 1] << 8));
            lastSecond[lastPos] = v; lastPos = (lastPos + 1) % lastSecond.length;
            short[] c = capture; if (c != null && captureLen < c.length) c[captureLen++] = v;
        }
    }

    /** The table shipped for this phone model (sets the calibration line), else the Armor's with a warning. */
    private float[] shippedTable() {
        float[] coef = load("m17/eq_" + profileId + ".txt");
        if (coef == null) { coef = load("m17/eq_ulefone-armor26ultra.txt"); calibration = Msg.of(R.string.modes_m17_cal_armor); }
        else calibration = Msg.of(profileId.startsWith("ulefone-armor26") ? R.string.modes_m17_cal_model : R.string.modes_m17_cal_phone);
        dfeNote = calibration;
        return coef;
    }

    private float[] load(String asset) {
        String[] lines = readLines(asset); if (lines == null || lines.length != M17Receiver.NCOEF) return null;
        float[] c = new float[lines.length]; for (int i = 0; i < c.length; i++) c[i] = Float.parseFloat(lines[i]); return c;
    }
    private double[] loadTaps(String asset) {
        String[] lines = readLines(asset); if (lines == null || lines.length != HeldPath.TAPS) return null;
        double[] c = new double[lines.length]; for (int i = 0; i < c.length; i++) c[i] = Double.parseDouble(lines[i]); return c;
    }
    private String[] readLines(String asset) {
        try (java.io.InputStream in = ctx.getAssets().open(asset)) { return new String(readAll(in), "UTF-8").trim().split("\\s+"); }
        catch (Exception e) { return null; }
    }
    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException { java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(); byte[] b = new byte[4096]; int k; while ((k = in.read(b)) > 0) o.write(b, 0, k); return o.toByteArray(); }

    private void fill(Heard h, M17.Lsf l) {
        h.src = l.src(); h.dst = l.dst(); h.meta = l.metaText(); h.encrypted = l.encryption() != 0; h.data = !l.voice();
    }
    private void onLink(M17.Lsf l) {
        Heard h = current; if (h == null) return;
        fill(h, l);
        host.event(h.meta != null ? Msg.of(R.string.modes_m17_event_call_meta, h.src, h.dst, h.meta) : Msg.of(R.string.modes_m17_event_call, h.src, h.dst));
        host.heard(h.src); host.changed();
    }
    private void remember(Text t) { synchronized (this) { texts.add(t); while (texts.size() > 100) texts.remove(0); } }
    private void onPacket(M17.Lsf l, byte[] body) {
        String src = l == null ? "" : l.src(), dst = l == null ? "" : l.dst();
        String sms = M17.smsText(body);
        Msg what = sms != null ? null : Msg.plural(R.plurals.modes_m17_packet, Math.max(0, body.length - 1), M17.protocolName(body.length == 0 ? -1 : body[0] & 0xFF), Math.max(0, body.length - 1));
        String shown = sms != null ? sms : what.text();                             // the M17 screen's list, in the app language
        remember(new Text(System.currentTimeMillis(), false, src, dst, shown));
        Log.i("M17Station", "sms " + src + " -> " + dst + " " + (sms != null ? sms : what.english()));
        host.text(sms != null ? Msg.of(R.string.modes_m17_event_sms, src, dst, sms) : Msg.of(R.string.modes_m17_event_packet, src, dst, what));   // history: English text, shown localized
        if (sms != null) host.sms(src, dst, sms, false);
        host.heard(src); host.changed();
    }
    private void noteLost() { host.text(Msg.of(R.string.modes_m17_frames_lost)); host.changed(); }
    private void begin(M17.Lsf l) {
        if (held == null) {                                               // the decision-feedback receiver: record this call for the self-calibration
            short[] c = new short[8000 * 10]; int k = 0;
            for (int i = 0; i < lastSecond.length; i++) c[k++] = lastSecond[(lastPos + i) % lastSecond.length];
            capture = c; captureLen = k;
        }
        Heard h = new Heard(System.currentTimeMillis());
        if (l.crcOk) fill(h, l);
        current = h;
        synchronized (this) { heard.add(h); while (heard.size() > 100) heard.remove(0); }
        if (l.crcOk) {
            host.event(h.meta != null ? Msg.of(h.encrypted ? R.string.modes_m17_event_call_meta_enc : R.string.modes_m17_event_call_meta, h.src, h.dst, h.meta)
                : Msg.of(h.encrypted ? R.string.modes_m17_event_call_enc : R.string.modes_m17_event_call, h.src, h.dst));
            host.heard(h.src);
        } else host.event(Msg.of(R.string.modes_m17_event_damaged));
        openTrack();
        host.changed();
    }
    private void onFrame(M17.Stream s, float q) {
        Heard h = current; if (h == null) return;
        h.frames++; h.end = System.currentTimeMillis(); quality = q;
        if (s.lich != null && s.lichErrors <= 2) h.good++;
        if (h.encrypted || h.data) return;
        Codec2 c = codec; AudioTrack t = track; if (c == null || t == null) return;
        try {
            short[] a = c.decode(java.util.Arrays.copyOfRange(s.payload, 0, 8)), b = c.decode(java.util.Arrays.copyOfRange(s.payload, 8, 16));
            short[] pcm = new short[a.length + b.length]; System.arraycopy(a, 0, pcm, 0, a.length); System.arraycopy(b, 0, pcm, a.length, b.length);
            t.write(pcm, 0, pcm.length, AudioTrack.WRITE_NON_BLOCKING);
            host.voice(pcm);
            if (benchDump) try (java.io.FileOutputStream o = new java.io.FileOutputStream(new java.io.File(ctx.getCacheDir(), "m17_out.raw"), true)) { byte[] by = new byte[pcm.length * 2]; for (int i = 0; i < pcm.length; i++) { by[2 * i] = (byte) pcm[i]; by[2 * i + 1] = (byte) (pcm[i] >> 8); } o.write(by); }
        } catch (Throwable e) { Log.w("M17Station", "voice: " + e); }
        if (h.frames % 12 == 0) host.changed();
    }
    private void finish(int frames, int good, String why) {
        if (swapping) return;
        Heard h = current; current = null;
        HeldPathReceiver hr = held;
        if (hr != null) {
            try {
                boolean kept = cal.onCall(hr.takeFrames(), hr.takeAudio(), h != null ? h.src : "", host.meter(), heldTaps);
                calibration = cal.lineMsg() == null ? calibration : cal.lineMsg();
                if (kept) { swapping = true; stopHeld(); setHeldPath(true); swapping = false; }
            } catch (Throwable t) { Log.w("M17Station", "fit: " + t); }
        }
        M17Receiver r = rx;
        if (hr == null && r != null && capture != null) {
            final float[] learned = r.learnedTable(DfeCal.MIN_FRAMES, M17Receiver.LEARN_FEEDBACK), live = dfeCoef;
            final int learnedN = r.learnedFrames(); final short[] audio = java.util.Arrays.copyOf(capture, captureLen);
            final String src = h != null ? h.src : ""; final int dbm = host.meter();
            capture = null;
            new Thread(() -> {
                try {
                    boolean kept = dfeCal.onCall(learned, learnedN, audio, src, dbm, live);
                    synchronized (M17Station.this) {
                        if (dfeCal.lineMsg() != null) calibration = dfeCal.lineMsg();
                        float[] own = kept ? dfeCal.accepted() : null;
                        if (own != null && running && held == null) { dfeCoef = own; rx = new M17Receiver(own, dfeEar); }
                    }
                    host.changed();
                } catch (Throwable t) { Log.w("M17Station", "dfe fit: " + t); }
            }, "m17-selfcal").start();
        }
        if (h != null) {
            h.end = System.currentTimeMillis();
            String secs = String.format(Locale.US, "%.1f", (h.end - h.start) / 1000.0); boolean clean = !(frames > 0 && good < frames); int pct = frames > 0 ? 100 * good / frames : 0;
            host.event(h.src.isEmpty() ? (clean ? Msg.of(R.string.modes_m17_event_ended, secs, frames) : Msg.of(R.string.modes_m17_event_ended_lossy, secs, frames, pct))
                : (clean ? Msg.of(R.string.modes_m17_event_ended_from, h.src, secs, frames) : Msg.of(R.string.modes_m17_event_ended_from_lossy, h.src, secs, frames, pct)));
        }
        AudioTrack t = track; if (t != null) { try { t.stop(); } catch (Throwable ignored) { } }   // plays out what is queued, then quiet
        host.changed();
    }

    private void openTrack() {
        try {
            if (track == null) {
                int min = AudioTrack.getMinBufferSize(8000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(8000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(min, 8000 * 2)).setTransferMode(AudioTrack.MODE_STREAM).build();
            }
            short[] lead = new short[1200]; track.write(lead, 0, lead.length, AudioTrack.WRITE_NON_BLOCKING);   // 150 ms of cushion for the frames' timing
            track.play();
        } catch (Throwable e) { Log.w("M17Station", "audio: " + e); }
    }
}
