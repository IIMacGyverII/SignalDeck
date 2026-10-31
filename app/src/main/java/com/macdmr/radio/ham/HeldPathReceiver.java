/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.ham;

import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Streaming wrapper around {@link HeldPath#live}. The receive thread only appends samples. Each pass searches every
 * sync chain after the last one consumed, skips the module's muted PCM, and drops audio up to the last decoded frame.
 * A transmission opens only from a link-setup CRC or six LICH chunks, so squelch noise does not become a second contact.
 *
 * Three rules keep a call whole (R-I, 2026-10-25): while a call is open the audio of every frame already given to
 * the listener is dropped from the buffer (keeping {@link #KEEP} samples for the filters' past), so a call is never
 * cut by the buffer cap; a frame whose end sits within {@link #EDGE} samples of the newest audio is left for the
 * next pass while audio is still arriving, because the equaliser and the matched filter would see zeros past it;
 * and a lost sync does not end the call by itself, only the end bit or two seconds of silence does, so a
 * one-second dropout is bridged when the sync comes back.
 */
public final class HeldPathReceiver {
    public interface Listener {
        void started(M17.Lsf lsf);
        /** A link setup that arrived after the transmission had already started. */
        void link(M17.Lsf lsf);
        void frame(M17.Stream s, float quality);
        void ended(int frames, int good, String why);
        /** A packet whose CRC checked. The body is the type byte and the payload, without the CRC. */
        default void packet(M17.Lsf lsf, byte[] body) { }
        /** The last frame arrived and the packet CRC failed, or a frame in the run was missing. */
        default void framesLost() { }
    }

    private static final Logger LOG = Logger.getLogger("HeldPath");   // i18n: logger name
    private static final int CAP = 20 * 8000;
    private static final int KEEP = 800;
    /** 8 kHz samples a frame must end before the newest audio while it is still arriving: the equaliser's 20 symbols past the frame (200 samples at 48 kHz) and the pulse span, with room. */
    static final int EDGE = 80;
    private static final long QUIET_MS = 2000;

    private final double[] taps;
    private final Listener listener;
    private final short[] buf = new short[CAP];
    private int len, ignore, lastN;
    private boolean open, stop, lsfSent;
    private M17.Lsf packetLsf;
    private final java.util.List<M17.Packet> packetFrames = new java.util.ArrayList<>();
    private int frames, good, emitted;
    private long lastEmit;
    private final java.util.ArrayList<HeldFit.Frame> kept = new java.util.ArrayList<HeldFit.Frame>();
    /** Longest call the receiver keeps (20 s) plus the second before it. */
    static final int RING = 21 * 8000;
    private final short[] ring = new short[RING];
    private int ringAt, callMark = -1, callWritten, callGap;
    private final Thread thread;

    public HeldPathReceiver(double[] taps, Listener listener) { this(taps, listener, true); }

    /** {@code background} false: a test calls {@link #poll} itself and no thread is started. */
    HeldPathReceiver(double[] taps, Listener listener, boolean background) {
        if (taps.length != HeldPath.TAPS) throw new IllegalArgumentException("taps");
        this.taps = taps.clone();
        this.listener = listener;
        if (!background) { thread = null; return; }
        thread = new Thread(this::loop, "m17-held");
        thread.setDaemon(true);
        thread.start();
    }

    /** 16-bit little-endian mono at 8 kHz. */
    public void feed(byte[] mono, int n) {
        if (stop) return;
        synchronized (buf) {
            for (int i = 0; i + 1 < n; i += 2) {
                if (len >= CAP) return;
                short s = (short) ((mono[i] & 0xFF) | (mono[i + 1] << 8));
                buf[len++] = s;
                ring[ringAt] = s;
                ringAt = (ringAt + 1) % ring.length;
                if (callMark >= 0) callWritten++;
            }
        }
    }

    /** Frames kept for a fit, and the audio of the call. Cleared after it is read. */
    public java.util.List<HeldFit.Frame> takeFrames() {
        java.util.List<HeldFit.Frame> out;
        synchronized (buf) { out = new java.util.ArrayList<HeldFit.Frame>(kept); kept.clear(); }
        return out;
    }
    public short[] takeAudio() {
        synchronized (buf) {
            if (callMark < 0) return new short[0];
            int total = callGap + callWritten;
            int start, n;
            if (total >= ring.length) { start = ringAt; n = ring.length; }   // the call overran the ring: the whole ring, oldest sample first
            else { start = callMark; n = total; }
            short[] o = new short[n];
            for (int i = 0; i < n; i++) o[i] = ring[(start + i) % ring.length];
            callMark = -1; callWritten = 0; callGap = 0;
            return o;
        }
    }

    /** Test: the next samples are the call, marked at the first of them. */
    void beginCallForTest() {
        synchronized (buf) { callMark = ringAt; callWritten = 0; callGap = 0; }
    }

    /** One decode of whatever is buffered. The background thread calls this; tests call it after {@link #feed}. */
    void poll() {
        short[] snap; int from; boolean growing;
        synchronized (buf) {
            growing = len > lastN; lastN = len;
            if (len - ignore < 800) { snap = null; from = ignore; }        // the search wants at least 100 ms past the resume point
            else { snap = Arrays.copyOf(buf, len); from = ignore; }
        }
        if (snap == null) {
            if (open && System.currentTimeMillis() - lastEmit > QUIET_MS) finish("signal lost");   // i18n: internal end reason, not shown
            return;
        }
        HeldPath.Live live = HeldPath.live(snap, snap.length, taps, from, open);
        take(live, snap, growing);
    }

    public void stop() { stop = true; if (thread != null) thread.interrupt(); finish("stopped"); }

    private void loop() {
        while (!stop) {
            try { Thread.sleep(200); }
            catch (InterruptedException e) { if (stop) return; }
            try { poll(); }
            catch (Throwable t) { report(t); }
        }
    }

    private void take(HeldPath.Live live, short[] snap, boolean growing) {
        int n = snap.length;
        boolean endBit = false, heldBack = false;
        long now = System.currentTimeMillis();
        int added = 0, index = 0, lastEnd8 = -1;
        outer:
        for (List<HeldPath.FrameHit> chain : live.chains) {
            M17.Lsf id = identity(chain);
            if (!open) {
                if (id == null) continue;
                open = true; frames = 0; good = 0; emitted = 0; lsfSent = true; lastEmit = now; kept.clear();
                packetFrames.clear(); packetLsf = id != null && !id.stream() ? id : null;
                callMark = (ringAt - 8000 + ring.length) % ring.length;
                callWritten = 0; callGap = 8000;
                listener.started(id); added++;
            } else if (id != null && !lsfSent) { lsfSent = true; listener.link(id); }
            for (HeldPath.FrameHit h : chain) {
                if (h.stream == null && h.packet == null) continue;
                int end8 = live.origin8 + (h.syncAt + 192 * HeldPath.SPS) / 6;
                if (index >= emitted) {
                    if (growing && end8 + EDGE > n) { heldBack = true; break outer; }   // its filters would see zeros past the newest audio: next pass
                    frames++;
                    if (h.stream != null) {
                        if (h.stream.lich != null && h.stream.lichErrors <= 2) good++;
                        keep(snap, live, h);
                        listener.frame(h.stream, Math.max(0f, Math.min(1f, h.score)));
                        endBit = h.stream.last();
                    } else {
                        packetFrames.add(h.packet);
                        if (h.packet.eof) {
                            M17.Lsf who = packetLsf != null ? packetLsf : id;
                            byte[] body = M17.assemble(packetFrames);
                            if (body != null && who != null) listener.packet(who, body);
                            else if (body == null) listener.framesLost();
                            endBit = true;
                        }
                    }
                    added++; lastEmit = now;
                    lastEnd8 = end8;
                }
                index++;
            }
        }
        if (index > emitted) emitted = index;
        if (!open) { advance(live.resume8, n); return; }
        // The end bit closes the call once nothing more of it is waiting: a bad frame can set the bit mid-call,
        // and the rest of that transmission is still audio.
        if (endBit && !heldBack) { finish("last frame"); return; }   // i18n: internal end reason, not shown
        if (lastEnd8 > 0) { consumed(lastEnd8); return; }                  // the call goes on: drop what the listener already has
        if (added == 0 && now - lastEmit > QUIET_MS) finish("signal lost");  // a lost sync alone does not end the call; silence does   // i18n: internal end reason, not shown
    }

    /** Keep a frame whose re-encoded symbols agree with the equalised slice. The raw window is what a later fit uses. */
    private void keep(short[] snap, HeldPath.Live live, HeldPath.FrameHit h) {
        if (h.stream == null || h.stream.lich == null || h.stream.lichErrors != 0 || h.stream.payload == null) return;
        if (kept.size() >= 400) return;
        int[] known = M17.streamSymbolsFromLich(h.stream.lich, h.stream.fn, h.stream.payload);
        double[] raw = HeldPath.rawWindow(snap, live.origin8, h.syncAt);
        if (raw == null) return;
        double[] eq = HeldFit.equalise(raw, known, taps);
        if (!HeldFit.targetOk(eq, known)) return;
        kept.add(new HeldFit.Frame(raw, known));
    }

    /** The chain's link setup, from its LSF or from six LICH chunks. Null when neither checks. */
    private M17.Lsf identity(List<HeldPath.FrameHit> chain) {
        M17.Lsf id = null;
        byte[][] part = new byte[6][];
        for (HeldPath.FrameHit h : chain) {
            if (h.link != null && h.link.crcOk) id = h.link;
            if (h.stream == null || h.stream.lich == null) continue;
            int c = h.stream.lichCount();
            if (c >= 0 && c <= 5) part[c] = Arrays.copyOf(h.stream.lich, 5);
        }
        boolean all = true; for (byte[] p : part) if (p == null) { all = false; break; }
        if (!all) return id;
        byte[] raw = new byte[30];
        for (int i = 0; i < 6; i++) System.arraycopy(part[i], 0, raw, 5 * i, 5);
        boolean ok = M17.crc16(raw, 28) == (((raw[28] & 0xFF) << 8) | (raw[29] & 0xFF));
        return ok ? new M17.Lsf(raw, true) : id;
    }

    /** Drop audio the search has finished with, keeping {@link #KEEP} samples so the next frame's filter has a past. */
    private void advance(int resume8, int n) {
        int end = Math.max(ignore, Math.min(resume8, n));
        synchronized (buf) { cut(end); }
    }

    /** During a call: everything before the end of the last frame the listener has is done with; the next pass starts there. */
    private void consumed(int end8) {
        synchronized (buf) {
            int end = Math.max(ignore, Math.min(end8, len));
            cut(end);
            emitted = 0;                                                   // the frames before the cut are gone from the buffer
            lastN = len;
        }
    }

    private void cut(int end) {
        int drop = end - KEEP;
        if (drop <= 0) { ignore = end; return; }
        if (drop >= len) { len = 0; ignore = 0; return; }
        System.arraycopy(buf, drop, buf, 0, len - drop);
        len -= drop;
        ignore = Math.min(KEEP, len);
    }

    private void finish(String why) {
        int f, g;
        synchronized (buf) {
            if (!open) { len = 0; ignore = 0; lastN = 0; return; }
            open = false; f = frames; g = good; frames = 0; good = 0; emitted = 0; lsfSent = false;
            packetFrames.clear(); packetLsf = null;
            len = 0; ignore = 0; lastN = 0;
        }
        listener.ended(f, g, why);
    }

    private static void report(Throwable t) {
        LOG.log(Level.WARNING, "decode", t);
        try {
            Class.forName("android.util.Log").getMethod("w", String.class, String.class, Throwable.class)
                .invoke(null, "HeldPath", "decode", t);   // i18n: log tag
        } catch (Throwable ignored) { }
    }
}
