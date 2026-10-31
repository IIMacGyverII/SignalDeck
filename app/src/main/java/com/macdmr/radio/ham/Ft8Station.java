/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.ham;

import com.macdmr.radio.i18n.Msg;
import com.macdmr.radio.i18n.Txt;
import com.macdmr.radio.R;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * An FT8 station on the current FM channel: collects each 15 s slot of receive audio (UTC slots from the phone clock),
 * decodes it, and runs the standard QSO sequence (CQ / grid / report / R+report / RR73 / 73) with a watchdog.
 * FM carries one transmitter at a time, so unlike SSB only the strongest station of a slot is heard.
 */
public final class Ft8Station {
    public interface Host {
        long now();                                                      // wall clock, ms (UTC slots)
        boolean canTransmit();                                           // an analog channel, not already transmitting
        void transmit(short[] pcm, String label, Runnable done);
        /** The 79 tones through the module's tone generator (bench firmware); false when the host cannot, and the audio path is used. */
        default boolean transmitTones(int[] tones, double f0, int leadMs, String label, Runnable done) { return false; }
        String callsign(); String grid();
        void event(String line);
        /** The same line kept as a message, so the history can show it in the app's language; by default its English. */
        default void event(Msg m) { event(m.english()); }
        void heard(String call, String grid, int snr);
        void logQso(String call, String grid, String sent, String rcvd);
        void decodeAsync(Runnable job);                                  // runs off the audio thread
    }
    public interface Listener { void changed(); }

    public static final int RATE = Ft8.RATE;
    /** Silence before the signal: the radio's TX path needs ~1.5 s after key-up, then FT8's 0.5 s slot offset. */
    public static final int TX_LEAD_MS = 1600;
    /** Key-up this long before the slot starts, so that after the lead-in the signal starts 0.5 s into the slot. */
    public static volatile int txAdvanceMs = TX_LEAD_MS - 500;
    /** From the transmit call to the first audio sample on air (bench-measured; the lead-in is shortened by it). */
    public static volatile int txLatencyMs = 320;                        // measured 2026-10-22 (HackRF, decoded DT of 3 transmissions): transmitPcm's 200 ms wait + playback start
    public static final int WATCHDOG = 8;                                // the same message unanswered this many times: stop

    /** One line of the decode list. */
    public static final class Heard {
        public final long slot; public final Ft8.Decode d; public final boolean toMe, cq; public final String from;
        Heard(long slot, Ft8.Decode d, boolean toMe, boolean cq, String from) { this.slot = slot; this.d = d; this.toMe = toMe; this.cq = cq; this.from = from; }
        public String time() { long s = slot * 15; return String.format(Locale.US, "%02d%02d%02d", (s / 3600) % 24, (s / 60) % 60, s % 60); }
    }

    private final Host host;
    private volatile Listener listener;
    final Ft8Message.Hashes hashes = new Ft8Message.Hashes();
    private final List<Heard> heard = new ArrayList<>();
    private final List<String> sent = new ArrayList<>();

    // receive buffer: 16 s ring, with the time of its newest sample
    private final float[] ring = new float[RATE * 16];
    private long written;                                                // samples ever written
    private long anchorMs = -1, anchorIdx;
    private long lastDecodedSlot = -1, lastTxSlot = -1;
    private volatile boolean decoding;

    // transmit state
    private volatile boolean running, txEnabled;
    private volatile int txParity;                                       // 0 = first (:00 :30), 1 = second (:15 :45)
    private volatile double txHz = 1500;
    private volatile String partner, partnerGrid, nextMsg, lastMsg, rptSent, rptRcvd;
    private volatile int step;                                           // 0 idle, 1-6 = the WSJT-X Tx1..Tx6 messages
    private volatile int repeats;
    private volatile long scheduledSlot = -1;
    private volatile Msg status = Msg.of(R.string.modes_ft8_status_off);

    public Ft8Station(Host host) { this.host = host; }
    public void setListener(Listener l) { listener = l; }
    private void changed() { Listener l = listener; if (l != null) l.changed(); }

    public boolean running() { return running; }
    public void start() { running = true; status = Msg.of(R.string.modes_ft8_status_listening); changed(); }
    public void stop() { running = false; txEnabled = false; step = 0; partner = null; nextMsg = null; status = Msg.of(R.string.modes_ft8_status_off); changed(); }
    public String status() { return status.text(); }
    /** The status as its message: English for DIAG ({@code statusMsg().english()}). */
    public Msg statusMsg() { return status; }
    public synchronized List<Heard> heard() { return new ArrayList<>(heard); }
    public synchronized List<String> sent() { return new ArrayList<>(sent); }
    public synchronized void clear() { heard.clear(); sent.clear(); changed(); }
    /** A decode by its slot and text (what a browser clicked). */
    public synchronized Heard find(long slot, String text) { for (int i = heard.size() - 1; i >= 0; i--) { Heard h = heard.get(i); if (h.slot == slot && h.d.text.equals(text)) return h; } return null; }
    public boolean txEnabled() { return txEnabled; }
    public int txParity() { return txParity; }
    public void setTxParity(int p) { txParity = p & 1; changed(); }
    public double txHz() { return txHz; }
    public void setTxHz(double hz) { txHz = Math.max(300, Math.min(2700, Math.round(hz))); changed(); }
    public String partner() { return partner; }
    public String nextMessage() { return nextMsg; }
    public int step() { return step; }

    static long slotOf(long ms) { return Math.floorDiv(ms, 15000L); }
    public long slotNow() { return slotOf(host.now()); }
    /** Seconds into the current slot (0-15). */
    public double secondsInSlot() { long t = host.now(); return (t - slotOf(t) * 15000L) / 1000.0; }

    // ---- receive -----------------------------------------------------------------------------------

    /** 16-bit little-endian mono audio at 8 kHz, as it arrives; the block's last sample is at {@code now}. */
    public void feed(byte[] mono, int n) {
        if (!running) return;
        int k = n / 2; long now = host.now();
        synchronized (this) {
            if (anchorMs >= 0) {                                         // audio missing (a transmission, a pause): fill the gap with silence
                long expect = anchorMs + k * 1000L / RATE, gap = now - expect;
                if (gap > 250) { int z = (int) Math.min(ring.length, gap * RATE / 1000); for (int i = 0; i < z; i++) ring[(int) (written++ % ring.length)] = 0; }
            }
            for (int i = 0; i < k; i++) ring[(int) (written++ % ring.length)] = (short) ((mono[2 * i] & 0xFF) | (mono[2 * i + 1] << 8)) / 32768f;
            anchorMs = now; anchorIdx = written;
        }
        maybeDecode(now);
    }

    /** Samples of [startMs, startMs + 15 s) that are still in the ring (zeros where there are none). */
    synchronized float[] slotAudio(long startMs) {
        float[] out = new float[RATE * 15];
        long first = anchorIdx - (anchorMs - startMs) * RATE / 1000;
        for (int i = 0; i < out.length; i++) { long j = first + i; if (j >= 0 && j < written && j > written - ring.length) out[i] = ring[(int) (j % ring.length)]; }
        return out;
    }

    private Ft8.MixedFft specFft;
    /** The latest 0.32 s of audio as {@code n} levels in dB between {@code fLo} and {@code fHi} Hz (the waterfall's next line). */
    public float[] spectrum(int n, double fLo, double fHi) {
        final int N = 2560; float[] x = new float[N];
        synchronized (this) { for (int i = 0; i < N; i++) { long j = written - N + i; x[i] = j >= 0 ? ring[(int) (j % ring.length)] : 0; } }
        if (specFft == null) specFft = new Ft8.MixedFft(N);
        double[] re = new double[N], im = new double[N];
        for (int i = 0; i < N; i++) { double s = Math.sin(Math.PI * i / N); re[i] = x[i] * s * s; }
        specFft.transform(re, im);
        float[] out = new float[n];
        for (int k = 0; k < n; k++) {
            int b0 = (int) ((fLo + (fHi - fLo) * k / n) * N / RATE), b1 = Math.max(b0 + 1, (int) ((fLo + (fHi - fLo) * (k + 1) / n) * N / RATE));
            double p = 0; for (int b = b0; b < b1 && b < N / 2; b++) p = Math.max(p, re[b] * re[b] + im[b] * im[b]);
            out[k] = (float) (10 * Math.log10(p + 1e-12));
        }
        return out;
    }

    private synchronized void maybeDecode(long now) {
        long slot = slotOf(now);
        if (now - slot * 15000L < 14_700 || slot == lastDecodedSlot || decoding) return;   // the signals end at 13.1 s; allow for a late clock
        lastDecodedSlot = slot;
        if (slot == lastTxSlot) return;                                  // our own transmission
        final float[] x = slotAudio(slot * 15000L);
        decoding = true;
        host.decodeAsync(() -> {
            try { onDecoded(slot, Ft8.decode(x, x.length, hashes)); }
            finally { decoding = false; }
        });
    }

    void onDecoded(long slot, List<Ft8.Decode> list) {
        String my = host.callsign() == null ? "" : host.callsign().toUpperCase(Locale.US);
        List<Heard> fresh = new ArrayList<>();
        for (Ft8.Decode d : list) {
            String[] w = d.text.split(" ");
            boolean cq = w[0].equals("CQ");
            String from = w.length >= 2 ? strip(w[cq && w.length >= 3 && isCqModifier(w[1]) ? 2 : 1]) : null;
            boolean toMe = !my.isEmpty() && w.length >= 2 && strip(w[0]).equals(my);
            fresh.add(new Heard(slot, d, toMe, cq, from));
            if (from != null && Logbook.looksLikeCall(from)) { String g = w.length >= 3 && isGrid(w[w.length - 1]) ? w[w.length - 1] : null; host.heard(from, g, d.snrDb); }
        }
        synchronized (this) { heard.addAll(fresh); while (heard.size() > 400) heard.remove(0); }
        status = list.isEmpty() ? Msg.of(R.string.modes_ft8_status_nothing) : Msg.plural(R.plurals.modes_ft8_status_decoded, list.size(), list.size());
        for (Heard h : fresh) if (h.toMe) answer(h);
        changed();
    }

    static String strip(String c) { return c.replace("<", "").replace(">", ""); }
    static boolean isGrid(String g) { return g.length() == 4 && g.charAt(0) >= 'A' && g.charAt(0) <= 'R' && g.charAt(1) >= 'A' && g.charAt(1) <= 'R' && Character.isDigit(g.charAt(2)) && Character.isDigit(g.charAt(3)) && !g.equals("RR73"); }
    static boolean isCqModifier(String s) { return s.matches("[A-Z]{1,4}|\\d{3}"); }
    static String report(int snr) { return String.format(Locale.US, "%+03d", Math.max(-30, Math.min(49, snr))); }

    // ---- QSO sequence ------------------------------------------------------------------------------

    private String msg(int s) {
        String my = host.callsign(), g = host.grid();
        String me = Ft8Message.standardCall(my) ? my : "<" + my + ">";    // a non-standard call of ours goes hashed next to a report, as WSJT-X sends it
        switch (s) {
            case 1: return partner + " " + my + (g != null && !g.isEmpty() ? " " + g : "");
            case 2: return partner + " " + me + " " + rptSent;
            case 3: return partner + " " + me + " R" + rptSent;
            case 4: return partner + " " + me + " RR73";
            case 5: return partner + " " + me + " 73";
            case 6: return "CQ " + my + (g != null && !g.isEmpty() ? " " + g : "");   // i18n: FT8 message text, sent on air
            default: return null;
        }
    }
    private void go(int s) { step = s; nextMsg = msg(s); repeats = 0; }

    /** Call CQ on the chosen period. */
    public String callCq() {
        String e = ready(); if (e != null) return e;
        partner = null; partnerGrid = null; rptSent = rptRcvd = null; logged = false; lastMsg = null; go(6); txEnabled = true; changed();
        return null;
    }

    /** Answer a station heard (normally a CQ): transmit in the other period, starting with our grid. */
    public String reply(Heard h) {
        String e = ready(); if (e != null) return e;
        if (h.from == null) return Txt.s(R.string.modes_ft8_err_no_call);
        partner = h.from; String[] w = h.d.text.split(" ");
        partnerGrid = w.length >= 3 && isGrid(w[w.length - 1]) ? w[w.length - 1] : null;
        rptSent = report(h.d.snrDb); rptRcvd = null; logged = false; lastMsg = null;
        txParity = (int) ((h.slot + 1) & 1);
        if (h.toMe) answer(h); else go(1);
        txEnabled = true; changed();
        return null;
    }

    /** Send a message typed by the user once (free text up to 13 characters, or any standard message). */
    public String sendText(String text) {
        String e = ready(); if (e != null) return e;
        if (Ft8Message.pack(text, null) == null) return Txt.s(R.string.modes_ft8_err_no_fit);
        step = 0; nextMsg = text.trim().toUpperCase(Locale.US); repeats = 0; txEnabled = true; changed();
        return null;
    }

    public void haltTx() { txEnabled = false; step = 0; nextMsg = null; changed(); }

    private String ready() {
        String my = host.callsign();
        if (my == null || my.isEmpty() || my.equals("N0CALL")) return Txt.s(R.string.modes_ft8_err_callsign);
        if (Ft8Message.pack("CQ " + my, null) == null) return Txt.s(R.string.modes_ft8_err_call_unsendable);   // i18n: FT8 message text, sent on air
        String g0 = host.grid();
        if (g0 != null && !g0.isEmpty() && Ft8Message.dropsGrid("CQ " + my + " " + g0)) host.event(Msg.of(R.string.modes_ft8_event_nonstandard));   // i18n: FT8 message text, sent on air
        if (!running) start();
        return null;
    }

    /** A message to us: move the sequence on, the way WSJT-X's auto-sequencing does. */
    void answer(Heard h) {
        String[] w = h.d.text.split(" ");
        if (w.length < 2) return;
        String from = strip(w[1]), last = w.length >= 3 ? w[w.length - 1] : "";
        if (partner != null && !from.equals(partner)) return;            // in a QSO with someone else
        if (partner == null) {
            if (step != 6) return;                                       // only a CQ invites new callers
            partner = from; partnerGrid = null; rptSent = report(h.d.snrDb); rptRcvd = null; logged = false;
            txParity = (int) ((h.slot + 1) & 1);
        }
        if (isGrid(last)) { partnerGrid = last; rptSent = report(h.d.snrDb); go(2); }
        else if (w.length == 2 && (step == 6 || step == 2)) { if (step == 6) rptSent = report(h.d.snrDb); go(2); }   // the calls only: a non-standard caller cannot add its grid; a repeat keeps the report
        else if (last.matches("[+-]\\d\\d")) { rptRcvd = last; if (rptSent == null) rptSent = report(h.d.snrDb); go(3); }
        else if (last.matches("R[+-]\\d\\d")) { rptRcvd = last.substring(1); go(4); logIt(); }
        else if (last.equals("RRR") || last.equals("RR73")) { go(5); logIt(); }
        else if (last.equals("73") && (step == 4 || step == 5)) {
            host.event(Msg.of(R.string.modes_ft8_event_complete, partner)); txEnabled = false; step = 0; nextMsg = null; partner = null; changed(); return;
        }
        else return;                                                     // nothing that moves the QSO on
        txEnabled = true;
        changed();
    }

    private boolean logged;
    private void logIt() {
        if (logged || partner == null) return;
        logged = true;
        host.logQso(partner, partnerGrid, rptSent, rptRcvd);
        host.event(Msg.of(R.string.modes_ft8_event_logged, partner + (partnerGrid != null ? " " + partnerGrid : ""), String.valueOf(rptSent), rptRcvd != null ? rptRcvd : "-"));
    }

    // ---- transmit scheduling: call this a few times a second ---------------------------------------

    public void tick() {
        if (!running) return;
        long now = host.now();
        // audio that comes only with receptions (the Atom XL opens its capture per reception) stops before 14.7 s: decode the slot from here
        synchronized (this) { if (anchorMs >= slotOf(now) * 15000L) maybeDecode(now); }
        if (!txEnabled || nextMsg == null) return;
        long next = slotOf(now) + 1;
        if ((next & 1) != txParity) return;
        long keyAt = next * 15000L - txAdvanceMs;
        if (now < keyAt || now > keyAt + 600 || scheduledSlot == next) return;
        if (!host.canTransmit()) return;
        String m = nextMsg; byte[] p = Ft8Message.pack(m, hashes);
        if (p == null) { status = Msg.of(R.string.modes_ft8_status_cannot_encode, m); txEnabled = false; changed(); return; }
        int limit = step == 4 ? 2 : WATCHDOG;                            // RR73 twice at most: the QSO is already logged
        if (m.equals(lastMsg)) { if (++repeats >= limit) { txEnabled = false; status = Msg.plural(R.plurals.modes_ft8_status_no_answer, WATCHDOG, WATCHDOG); host.event(Msg.of(R.string.modes_ft8_event_no_answer, m)); changed(); return; } }
        else repeats = 0;
        lastMsg = m; scheduledSlot = next; lastTxSlot = next;
        // the lead-in from the actual moment of keying: a late tick is absorbed, the signal still starts 0.5 s into the slot
        int leadMs = (int) Math.max(0, next * 15000L + 500 - now - txLatencyMs);
        short[] pcm = Ft8.pcm(p, txHz, leadMs, 0.7);
        synchronized (this) { long s = next * 15; sent.add(String.format(Locale.US, "%02d%02d%02d  %4.0f  %s", (s / 3600) % 24, (s / 60) % 60, s % 60, txHz, m)); while (sent.size() > 200) sent.remove(0); }
        status = Msg.of(R.string.modes_ft8_status_transmitting, m);
        final int stepAtTx = step;
        Runnable after = () -> {
            status = Msg.of(R.string.modes_ft8_status_listening);
            if (stepAtTx == 5) { txEnabled = false; step = 0; nextMsg = null; partner = null; host.event(Msg.of(R.string.modes_ft8_event_sent_73)); }
            else if (stepAtTx == 0) { txEnabled = false; nextMsg = null; }   // a typed message goes once
            changed();
        };
        if (!host.transmitTones(Ft8.tones(p), txHz, leadMs, "FT8 " + m, after)) host.transmit(pcm, "FT8 " + m, after);
        changed();
    }
}
