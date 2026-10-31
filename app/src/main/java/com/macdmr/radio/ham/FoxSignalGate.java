/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio.ham;

import com.macdmr.radio.i18n.Txt;
import com.macdmr.radio.R;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Which signal readings a fox hunt may use. With "Only the fox's", a reading counts only while the fox itself is heard
 * (its CTCSS decoded from the audio, the channel's DCS squelch open, or on DMR its caller ID and colour code), from
 * {@link #START_MS} into its transmission and {@link #SETTLE_MS} after a retune (the module's reading lags a retune), and
 * not in the last {@link #TAIL_MS} before the match ends (the squelch tail: the carrier is already gone). A reading is
 * therefore confirmed {@link #TAIL_MS} after it arrives. With "Any signal" a reception of anything is a transmission.
 *
 * Also the waiting mark (Mark while the fox is silent takes the mark at its next transmission) and the auto-mark rule
 * (one mark per transmission once the hunter has moved far enough). Pure Java: the dialog feeds it each tick.
 */
public final class FoxSignalGate {
    public static final long SETTLE_MS = 700, START_MS = 300, TAIL_MS = 600;
    /** Confirmed readings a transmission needs before a mark is taken from it, and how many recent ones the mark averages. */
    public static final int MIN_READINGS = 2, AVG_READINGS = 4;
    public static final double DEFAULT_MOVE_M = 50;

    /** What identifies the fox. */
    public static final class Fox {
        public boolean only;            // false = any signal (today's behaviour)
        public boolean digital;         // a DMR channel: caller ID and colour code; else analog tones
        public double toneHz;           // analog: the fox's CTCSS (Hz), 0 = none
        public boolean dcs;             // analog: the channel's DCS squelch picks the fox (the module decides; audio cannot)
        public int id;                  // DMR: the fox's ID, 0 = any caller
        public int cc = -1;             // DMR: its colour code, -1 = any
        /** A short description for the sheet. */
        public String describe() {
            if (!only) return Txt.s(R.string.modes_fox_any_signal);
            if (digital) return id > 0 ? (cc >= 0 ? Txt.s(R.string.modes_fox_dmr_id_cc, String.valueOf(id), String.valueOf(cc)) : Txt.s(R.string.modes_fox_dmr_id, String.valueOf(id)))
                : (cc >= 0 ? Txt.s(R.string.modes_fox_dmr_any_cc, String.valueOf(cc)) : Txt.s(R.string.modes_fox_dmr_any));
            if (dcs) return Txt.s(R.string.modes_fox_dcs);
            return toneHz > 0 ? Txt.s(R.string.modes_fox_ctcss, Txt.decimal(toneHz, 1)) : Txt.s(R.string.modes_fox_no_tone);
        }
    }

    /** What the radio says right now. */
    public static final class Now {
        public long t;
        public boolean rx;              // a reception under way (the app's squelch, or the module's)
        public boolean mcuRx;           // the module's own squelch is open (for a DCS fox)
        public boolean softSq;          // Soft SQ holds the module's squelch open: DCS cannot pick the fox then
        public boolean tx;              // we are transmitting
        public double toneHz;           // CTCSS decoded from the audio (Hz), 0 = none
        public int caller;              // DMR caller during a call, 0 = none / not known yet
        public int cc = -1;             // DMR colour code reported with the call, -1 = not reported
        public int channelCc = -1;      // the channel's colour code (the module passes only that one when it reports none)
    }

    /** A confirmed reading: when, how strong, and where the phone pointed then (NaN = no compass). */
    public static final class Reading {
        public final long at; public final int dbm; public final double heading;
        public Reading(long at, int dbm, double heading) { this.at = at; this.dbm = dbm; this.heading = heading; }
    }

    public static boolean matches(Fox f, Now n) {
        if (n.tx || !n.rx) return false;
        if (f == null || !f.only) return true;
        if (f.digital) {
            if (f.id > 0 && n.caller != f.id) return false;
            int cc = n.cc >= 0 ? n.cc : n.channelCc;
            return f.cc < 0 || cc == f.cc;
        }
        if (f.dcs) return n.mcuRx && !n.softSq;
        if (f.toneHz > 0) return n.toneHz > 0 && Math.abs(n.toneHz - f.toneHz) < 1.5;
        return true;
    }

    private Fox fox = new Fox();
    private boolean on, waiting; private long runStart, runId, takenRun = -1, lastReadingAt = Long.MIN_VALUE;
    private final ArrayDeque<Reading> pending = new ArrayDeque<>();
    private final List<Reading> run = new ArrayList<>(), out = new ArrayList<>();

    public void setFox(Fox f) { fox = f == null ? new Fox() : f; on = false; waiting = false; pending.clear(); run.clear(); }
    public Fox fox() { return fox; }

    /** Each tick: is a matching transmission under way? Returns that. */
    public boolean update(Now n) {
        boolean m = matches(fox, n);
        if (m && !on) { on = true; runStart = n.t; runId++; run.clear(); pending.clear(); }
        if (on) while (!pending.isEmpty() && pending.peekFirst().at <= n.t - TAIL_MS) { Reading r = pending.pollFirst(); run.add(r); out.add(r); }
        if (!m && on) { on = false; pending.clear(); }                 // what is still pending fell in the tail
        return on;
    }

    /** A signal reading (arrival time, dBm, compass heading). Kept when it is new, inside a match, past the start and the retune settle. */
    public void reading(long at, int dbm, double heading, long tunedAt) {
        if (at <= lastReadingAt || dbm == -999) return;
        lastReadingAt = at;
        if (!on || at < runStart + START_MS || at < tunedAt + SETTLE_MS) return;
        pending.addLast(new Reading(at, dbm, heading));
    }

    /** True while a matching transmission is under way. */
    public boolean active() { return on; }
    /** The current transmission has enough confirmed readings for a mark. */
    public boolean ready() { return on && run.size() >= MIN_READINGS; }
    /** The mean of the transmission's latest confirmed readings (dBm), or -999. */
    public int level() {
        if (run.isEmpty()) return -999;
        int k = Math.min(AVG_READINGS, run.size()); double s = 0;
        for (int i = run.size() - k; i < run.size(); i++) s += run.get(i).dbm;
        return (int) Math.round(s / k);
    }
    /** Confirmed readings since the last call (for the spin). */
    public List<Reading> drain() { List<Reading> r = new ArrayList<>(out); out.clear(); return r; }

    // ---- the waiting mark ----
    public void requestMark() { waiting = true; }
    public void cancel() { waiting = false; }
    public boolean waiting() { return waiting; }
    /** The level for the requested mark once a matching transmission has enough readings (then no longer waiting), else -999. */
    public int takeIfReady() {
        if (!waiting || !ready()) return -999;
        waiting = false; takenRun = runId; return level();
    }

    // ---- auto-marks ----
    /** Auto-marks on: at a matching transmission not marked yet, when the hunter moved more than {@code minM} since the last mark (NaN = no mark yet). The level, or -999. */
    public int autoTake(boolean autoOn, double movedM, double minM) {
        if (!autoOn || !ready() || takenRun == runId) return -999;
        if (!Double.isNaN(movedM) && movedM <= minM) return -999;
        takenRun = runId; return level();
    }

    /** The mean heading when the last headings agree within {@code spreadDeg} of it (the phone held steady), else NaN. */
    public static double steadyHeading(double[] hs, double spreadDeg) {
        double sx = 0, sy = 0; int n = 0;
        for (double h : hs) { if (Double.isNaN(h)) return Double.NaN; sx += Math.sin(Math.toRadians(h)); sy += Math.cos(Math.toRadians(h)); n++; }
        if (n < 2) return Double.NaN;
        double mean = (Math.toDegrees(Math.atan2(sx, sy)) + 360) % 360;
        for (double h : hs) if (Math.abs(diff(h, mean)) > spreadDeg) return Double.NaN;
        return mean;
    }
    /** a - b folded into -180..180 degrees. */
    public static double diff(double a, double b) { double d = ((a - b) % 360 + 540) % 360 - 180; return d; }
}
