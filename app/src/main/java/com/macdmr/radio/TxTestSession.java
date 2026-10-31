/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * The guided transmit test session (Device → Device report → Transmit test session), the steps of
 * docs/DEVICE_CERTIFICATION.md section 3.3 for a tester who made a fork to support their phone: FM voice key, release
 * check, the limit check (the session's own 28 s limit), frequency and deviation, a DMR group call (DMR phones), heat. SignalDeck keys the radio itself
 * through its normal transmit path, so every guard of that path applies (receive-only channels, firmware loading, an
 * unverified profile); for each key it records what it saw ({@link TxReport.Key}), and asks the tester what only they
 * can know. The result is tx-report.json in the current device report folder plus a section in report.txt and
 * 00_SUMMARY.txt, with the configured callsign and DMR ID replaced. Runs on its own thread; the screen is {@link Ui}.
 * i18n: stays English (developer report/bench text, plan §1)
 */
public final class TxTestSession {

    /** The screens the session needs; each call blocks the session thread until the tester answers. */
    public interface Ui {
        /** An instruction with buttons; returns the index tapped, or -1 when the session is stopped. */
        int choose(String title, String text, String... buttons) throws InterruptedException;
        /** A form of text fields; returns their values, or null when cancelled. */
        String[] form(String title, String text, String[] labels, String[] values, boolean[] numeric) throws InterruptedException;
        /** Pick a recording; returns {name, sha256, bytes} or null. */
        String[] pickRecording() throws InterruptedException;
        /** While a key is down: the live line, with a Release now button that calls {@link #releaseNow}. */
        void keying(String text);
        void keyingDone();
    }

    private final RadioService svc; private final Ui ui; private final Context c;
    private volatile boolean release;
    public final TxReport report = new TxReport();

    public TxTestSession(RadioService svc, Context c, Ui ui) { this.svc = svc; this.c = c; this.ui = ui; }

    /** The tester's Release now during a key. */
    public void releaseNow() { release = true; }

    /** Whether the session may start on this phone now (null) or why not. */
    public static TxReport.Gate gate(RadioService svc, BuildIdentity.Info b) {
        DeviceProfile p = svc.profile();
        boolean allowed = svc.modeAllowed("txtest") && svc.adminAllowed("txtest");   // Restricted mode (PR 26) is not merged yet: both are stubs that allow; the merge keeps these calls
        return TxReport.gate(p.verified, p.txTestUnverified, p.txBlocked || ProfileRegistry.transmitBlocked(), allowed, b.kind);
    }

    static String pttPath(DeviceProfile p) {
        String app = "SignalDeck's own key (the same path as the on-screen Talk key)";
        if ("agold".equals(p.protocol)) return app + " -> the USB bridge's PTT line (" + AgoldUsb.HANDLE_PTT + ")";
        if ("agold-legacy".equals(p.protocol)) return app + " -> extmodule's PTT control (3 / 4) and transmit audio on its audio socket; no module key command"
                + (p.txReleaseHoldMs > 0 ? "; key and microphone held " + p.txReleaseHoldMs + " ms past the release" : "");
        if ("sa818".equals(p.protocol) || p.rxLinesOn != null) return app + " -> phone lines " + lines(p.txLinesOn);
        if (p.txLinesOn != null) return app + " -> phone lines " + lines(p.txLinesOn) + ", released with " + lines(p.txLinesOff) + "; no 0x26"
                + (p.txAudioOnSendStart ? "; the audio starts at the module's SEND_START (none sent without it)" : "")
                + (p.txAudioTailMs > 0 ? "; the microphone runs " + p.txAudioTailMs + " ms past the release" : "")
                + (p.minKeyGapMs > 0 ? "; a key within " + p.minKeyGapMs + " ms of the last release waits for the rest" : "");
        String t = (p.txAudioLeadMs > 0 ? "; the audio path opens " + p.txAudioLeadMs + " ms before the key, so the module keys that much after the press" : "")
                + (p.minKeyGapMs > 0 ? "; a key within " + p.minKeyGapMs + " ms of the last release waits for the rest" : "");
        return app + " -> module command 0x26 (launch) on " + p.controlPort + t;
    }
    private static String lines(String[][] l) { if (l == null) return "(none)"; StringBuilder s = new StringBuilder(); for (String[] x : l) s.append(s.length() > 0 ? ", " : "").append(x[0]).append('=').append(x[1]); return s.toString(); }

    static TxReport.Channel channel(ChannelStore.Channel ch) {
        if (ch == null) return null;
        TxReport.Channel c = new TxReport.Channel();
        c.number = ch.number; c.name = ch.name; c.mode = ch.isM17() ? "M17" : ch.isDigital() ? "DMR" : "FM"; c.rxHz = ch.rxFreq; c.txHz = ch.txFreq;
        c.power = ch.power == 1 ? "high" : "low"; c.bandwidth = ch.band == 0 ? "narrow" : "wide";
        if (ch.isDigital()) { c.colorCode = ch.cc; c.slot = ch.outSlot + 1; c.talkgroup = ch.txContact; }
        return c;
    }

    // ---- one key ------------------------------------------------------------------------------------------------------

    /**
     * Key once through SignalDeck's transmit path and record it. {@code ms} > 0: SignalDeck releases after that long;
     * 0: SignalDeck does not release, its watchdog must at the session's 28 s limit (the tester can still release, and at 35 s the session does).
     */
    TxReport.Key key(String what, long ms) throws InterruptedException {
        svc.txTestKeying = true;                                                      // an untested profile with txTestUnverified keys only for this
        try { return keyOnce(what, ms); } finally { svc.txTestKeying = false; }
    }

    private TxReport.Key keyOnce(String what, long ms) throws InterruptedException {
        TxReport.Key k = new TxReport.Key(); k.plannedMs = ms; k.pttPath = pttPath(svc.profile());
        ChannelStore.Channel ch = svc.current(); k.channel = channel(ch);
        long now0 = System.currentTimeMillis(); k.startUtc = TxReport.utc(now0);
        if (!svc.isReady()) { k.refused = "the radio is not ready"; k.releasedBy = "not keyed"; return k; }
        if (svc.txInhibited()) { k.refused = "transmit is off on this channel (receive-only channel, or the profile)"; k.releasedBy = "not keyed"; return k; }
        k.batteryBefore = nan(ThermalGuard.batteryC(svc)); k.processorBefore = nan(svc.thermal.readC());
        release = false;
        final long[] t0 = { 0 };
        final TxReport.Key kk = k;
        svc.statusTap = (code, name) -> { synchronized (kk) { TxReport.Status s = new TxReport.Status(); s.tMs = System.currentTimeMillis() - t0[0]; s.code = code; s.name = name; kk.statuses.add(s);
            if (code == 3 && kk.sendStartMs < 0) kk.sendStartMs = s.tMs; if (code == 4 && kk.sendStartMs >= 0) kk.sendStopMs = s.tMs; } };
        t0[0] = System.currentTimeMillis();
        long releasedAt = -1;
        try {
            svc.ptt(true);
            long limit = ms > 0 ? ms : 35_000;
            boolean seenHeld = false;
            while (true) {
                Thread.sleep(100);
                long el = System.currentTimeMillis() - t0[0];
                boolean held = svc.pttHeld(); if (held) seenHeld = true;
                if (!held && (seenHeld || el > 1500)) {                              // released without us: the watchdog, the module, or a guard refused the key
                    releasedAt = el;
                    if (!seenHeld) { k.refused = "SignalDeck did not key (see Device → Diagnostics for the reason)"; k.releasedBy = "not keyed"; }
                    else k.releasedBy = ms == 0 && el >= 26_000 ? "watchdog" : "module";
                    break;
                }
                if (release) { svc.ptt(false); releasedAt = el; k.releasedBy = "tester"; break; }
                if (el >= limit) { svc.ptt(false); releasedAt = el; k.releasedBy = ms > 0 ? "app" : "session (35 s, the watchdog did not release)"; break; }
                ui.keying(String.format(Locale.US, "%s\n\nTransmitting %.0f s%s · module: %s", what, el / 1000.0, ms > 0 ? " of " + ms / 1000 : "", lastStatus(k)));
            }
            k.durationMs = releasedAt;
            long until = System.currentTimeMillis() + 2500;                          // the module's SEND_STOP after the release
            while (System.currentTimeMillis() < until) { synchronized (k) { if (k.sendStopMs >= 0 && k.sendStopMs >= releasedAt - 50) break; } Thread.sleep(100); }
        } finally {
            if (svc.pttHeld()) svc.ptt(false);
            svc.statusTap = null;
            ui.keyingDone();
        }
        k.batteryAfter = nan(ThermalGuard.batteryC(svc)); k.processorAfter = nan(svc.thermal.readC());
        return k;
    }
    private static Double nan(double d) { return Double.isNaN(d) ? null : d; }
    private static String lastStatus(TxReport.Key k) { synchronized (k) { return k.statuses.isEmpty() ? "no reply yet" : k.statuses.get(k.statuses.size() - 1).name; } }

    // ---- the session --------------------------------------------------------------------------------------------------

    private static final int GO = 0, SKIP = 1;

    /**
     * Run every step (session thread). The safety rules were confirmed on the screen before; {@code safetyUtc} is when.
     * For the whole run the transmit limit is the session's own {@link TxLimit#TEST_SESSION_S} (28 s), whatever the
     * user's Longest transmission says, so the limit check stays one short key; the setting is back when it ends.
     */
    public void run(BuildIdentity.Info build, long safetyMs) throws InterruptedException {
        svc.setTxLimitOverride(TxLimit.TEST_SESSION_S);
        try { steps(build, safetyMs); } finally { svc.setTxLimitOverride(0); }
    }

    private void steps(BuildIdentity.Info build, long safetyMs) throws InterruptedException {
        TxReport r = report;
        r.created = TxReport.utc(System.currentTimeMillis()); r.build = build; r.module = svc.moduleVersion().trim(); r.safetyUtc = TxReport.utc(safetyMs);
        for (String s : TxReport.SAFETY) r.safety.add(s);
        try { r.phone.put("manufacturer", android.os.Build.MANUFACTURER).put("model", android.os.Build.MODEL).put("device", android.os.Build.DEVICE).put("android", android.os.Build.VERSION.RELEASE); } catch (Throwable ignored) { }
        boolean dmr = svc.profile().dmr() && svc.engine().dmr();
        r.steps.addAll(TxReport.plan(dmr, svc.profile().smsUtf8));

        String[] eq = ui.form("Your test equipment", "What checks your signal. Do not enter your callsign or DMR ID; SignalDeck removes them anyway.",
                new String[] { "Measuring equipment (e.g. RTL-SDR v4, service monitor)", "Receiving radio (e.g. a handheld's make and model)" }, new String[] { "", "" }, new boolean[] { false, false });
        if (eq == null) { finish(); return; }
        r.equipment = eq[0].trim(); r.receiver = eq[1].trim();

        // 1 FM voice
        TxReport.Step s = r.step("fm");
        if (fmChannel(s) && ask(s, "Key 5 s", "SignalDeck keys the radio for 5 seconds on this FM channel and releases it. Speak or stay silent; the receiving radio must hear the carrier and your voice clearly, at a normal level.")) {
            TxReport.Key k = key(s.title, 5000); s.keys.add(k);
            heard(s, "Did the receiving radio hear it clearly, at a normal level?", false);
            s.auto = TxReport.keyFinding(k); s.result = TxReport.heardVerdict(k, s.heard);
        } else if (stopped) { finish(); return; }

        // 2 Release
        s = r.step("release");
        if (fmChannel(s) && ask(s, "Start (three keys)", "Three keys of 2 seconds with a 3 second pause. Each must end when SignalDeck releases it: the module reports SEND_STOP and the receiving radio goes quiet every time.")) {
            for (int i = 0; i < 3 && !stopped; i++) { s.keys.add(key(s.title + String.format(Locale.US, " · key %d of 3", i + 1), 2000)); if (i < 2) Thread.sleep(3000); }
            heard(s, "Did the receiving radio go quiet each time the key was released?", false);
            StringBuilder a = new StringBuilder(); for (TxReport.Key k : s.keys) a.append(a.length() > 0 ? "\n" : "").append(TxReport.keyFinding(k));
            s.auto = a.toString(); s.result = TxReport.releaseVerdict(s.keys);
            if (TxReport.PASS.equals(s.result) && "no".equals(s.heard)) s.result = TxReport.FAIL;
        } else if (stopped) { finish(); return; }

        // 3 The limit check: the session's own 28 s limit, whatever Longest transmission is set to
        s = r.step("watchdog");
        if (fmChannel(s) && ask(s, "Hold one key", "One key that SignalDeck does not release: its transmit limit must end it. For this session the limit is 28 seconds, whatever Device \u2192 Longest transmission says. This is the only long key of the session; stay with the phone and tap Release now if anything looks wrong (the step then counts as skipped).")) {
            TxReport.Key k = key(s.title, 0); s.keys.add(k);
            s.auto = TxReport.keyFinding(k); s.result = TxReport.watchdogVerdict(k);
            heard(s, "Did the receiving radio go quiet after about 28 seconds?", false);
            if (TxReport.PASS.equals(s.result) && "no".equals(s.heard)) s.result = TxReport.FAIL;
        } else if (stopped) { finish(); return; }

        // 4 Frequency and deviation
        s = r.step("measure");
        int m = stopped ? -1 : ui.choose(s.title, "With your measuring equipment, note how far the carrier is from the channel frequency (a few hundred Hz is normal) and, if it shows it, the peak deviation while you speak. Key 10 s while you measure, or enter values you already have.",
                "Key 10 s and measure", "Enter values", "Skip this step", "Stop the session");
        if (m == 3 || m < 0) { stopped = true; finish(); return; }
        if (m == 0 && fmChannel(s)) { TxReport.Key k = key(s.title + " · measure now", 10_000); s.keys.add(k); s.auto = TxReport.keyFinding(k); }
        if (stopped) { finish(); return; }
        if (m == 0 || m == 1) {
            String[] v = ui.form(s.title, "Leave a field empty if you could not measure it.", new String[] { "Carrier offset from the channel frequency (Hz, e.g. +600 or -350)", "Peak deviation (Hz, e.g. 2500)", "Notes (method, settings)" },
                    new String[] { "", "", "" }, new boolean[] { true, true, false });
            if (v != null) { s.offsetHz = TxReport.parseHz(v[0]); s.deviationHz = TxReport.parseHz(v[1]); s.notes = v[2].trim(); }
            s.result = s.offsetHz != null || s.deviationHz != null ? TxReport.RECORDED : TxReport.SKIPPED;
        } else s.result = TxReport.SKIPPED;

        // 5 DMR group call
        s = r.step("dmr");
        if (s != null) {
            ChannelStore.Channel ch = svc.current();
            int a = ch != null && ch.isDigital() ? GO : ui.choose(s.title, "Choose a DMR channel on the Intercom page whose talkgroup the other DMR radio listens to (same colour code and time slot), then come back and tap Ready.", "Ready", "Skip this step", "Stop the session");
            if (a == 2 || a < 0) { stopped = true; finish(); return; }
            ch = svc.current();
            if (a == GO && (ch == null || !ch.isDigital())) { s.result = TxReport.SKIPPED; s.auto = "no DMR channel selected"; }
            else if (a == GO && ask(s, "Key 5 s", String.format(Locale.US, "SignalDeck makes a 5 second group call to talkgroup %d on time slot %d, colour code %d. The other DMR radio must hear it and show your caller ID.", ch.txContact, ch.outSlot + 1, ch.cc))) {
                TxReport.Key k = key(s.title, 5000); s.keys.add(k);
                heard(s, "Did the other DMR radio hear the call?", true);
                s.auto = TxReport.keyFinding(k); s.result = TxReport.heardVerdict(k, s.heard);
                if (TxReport.PASS.equals(s.result) && "no".equals(s.callerId)) s.result = TxReport.FAIL;
            } else if (stopped) { finish(); return; } else if (a == SKIP) s.result = TxReport.SKIPPED;
        }

        // 5b DMR text (phones whose text body is QuickChat's, not the Armor's)
        s = r.step("text");
        if (s != null && !stopped) {
            ChannelStore.Channel ch = svc.current();
            int a = ch != null && ch.isDigital() ? GO : ui.choose(s.title, "Choose a DMR channel whose call number the other DMR radio receives texts on (its own ID for a private text, or a talkgroup it listens to), then come back and tap Ready.", "Ready", "Skip this step", "Stop the session");
            if (a == 2 || a < 0) { stopped = true; finish(); return; }
            ch = svc.current();
            if (a == GO && (ch == null || !ch.isDigital())) { s.result = TxReport.SKIPPED; s.auto = "no DMR channel selected"; }
            else if (a == GO && ask(s, "Send the text", String.format(Locale.US, "SignalDeck sends the text \"%s\" to %s %d. The module reports whether it was delivered; then check the other radio.", TEXT, ch.contactType == 1 ? "talkgroup" : "radio", ch.txContact))) {
                int st = text(ch);
                s.auto = "body " + svc.profile().textBody() + "; module: " + (st == 8 ? "status 8, delivered" : st == 9 ? "status 9, not delivered" : "no answer in 15 s");
                int b = ui.choose(s.title, "Did the other radio show \"" + TEXT + "\" exactly (no missing or wrong characters)?", "Yes", "No", "Not checked");
                s.heard = b == 0 ? "yes" : b == 1 ? "no" : "not checked";
                String[] n = ui.form(s.title, "What the other radio showed, if it differed (optional).", new String[] { "Notes" }, new String[] { "" }, new boolean[] { false });
                if (n != null) s.notes = n[0].trim();
                s.result = TxReport.textVerdict(st, s.heard);
            } else if (stopped) { finish(); return; } else if (a == SKIP) s.result = TxReport.SKIPPED;
        }

        // 6 Heat
        s = r.step("heat");
        if (fmChannel(s) && ask(s, "Start (five keys)", "Five keys of 3 seconds with a 5 second pause. SignalDeck reads the battery and processor temperatures before the first and after the last. If the phone feels hot, tap Release now and stop.")) {
            for (int i = 0; i < 5 && !stopped && !release; i++) { s.keys.add(key(s.title + String.format(Locale.US, " · key %d of 5", i + 1), 3000)); if (i < 4) Thread.sleep(5000); }
            TxReport.Key f = s.keys.isEmpty() ? null : s.keys.get(0), l = s.keys.isEmpty() ? null : s.keys.get(s.keys.size() - 1);
            if (f != null) {
                s.auto = String.format(Locale.US, "%d keys · battery %s → %s °C · processor %s → %s °C", s.keys.size(), d(f.batteryBefore), d(l.batteryAfter), d(f.processorBefore), d(l.processorAfter));
                s.result = l.batteryAfter != null && l.batteryAfter >= ThermalGuard.BATTERY_START_C ? TxReport.FAIL : TxReport.RECORDED;
            }
        } else if (stopped) { finish(); return; }

        // Recording
        int rc = ui.choose("A recording (optional)", "A recording of your transmissions from the receiving radio or the SDR helps the maintainer check your results. Pick the file on this phone (SignalDeck stores its name and SHA-256, not the file), or give a link where you uploaded it.",
                "Pick a file", "Enter a link", "No recording");
        if (rc == 0) {
            String[] p = ui.pickRecording();
            if (p != null) { TxReport.Recording x = new TxReport.Recording(); x.name = p[0]; x.sha256 = p[1]; try { x.bytes = Long.parseLong(p[2]); } catch (Throwable ignored) { } r.recordings.add(x); }
        } else if (rc == 1) {
            String[] v = ui.form("Recording link", "The link to the uploaded recording and, if you have it, its SHA-256 (sha256sum on a computer).", new String[] { "Link (URL)", "SHA-256 (optional)" }, new String[] { "", "" }, new boolean[] { false, false });
            if (v != null && !v[0].trim().isEmpty()) { TxReport.Recording x = new TxReport.Recording(); x.url = v[0].trim(); x.sha256 = TxReport.sha256OrNull(v[1]); r.recordings.add(x); }
        }
        finish();
    }

    private boolean stopped;
    static final String TEXT = "SignalDeck test 1";

    /** Send {@link #TEXT} to the channel's call number; the module's status 8 or 9, or 0 when neither came within 15 s. */
    private int text(ChannelStore.Channel ch) throws InterruptedException {
        final int[] st = { 0 };
        ChannelStore.Contact to = new ChannelStore.Contact(); to.number = ch.txContact; to.type = ch.contactType; to.name = "Transmit test";
        svc.statusTap = (code, name) -> { if (code == 8 || code == 9) synchronized (st) { if (st[0] == 0) st[0] = code; } };
        svc.txTestKeying = true;                                                      // a text transmits too: the same session-only permission
        try {
            svc.sendSms(to, TEXT);
            long end = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < end) { synchronized (st) { if (st[0] != 0) break; } ui.keying(sending(ch) + "\n\nWaiting for the module's answer\u2026"); Thread.sleep(200); }
        } finally { svc.txTestKeying = false; svc.statusTap = null; ui.keyingDone(); }
        synchronized (st) { return st[0]; }
    }
    private static String sending(ChannelStore.Channel ch) { return "Sending a DMR text to " + ch.txContact; }
    private static String d(Double v) { return v == null ? "?" : String.format(Locale.US, "%.1f", v); }

    /** An FM channel is needed: ask the tester to choose one, or skip. */
    private boolean fmChannel(TxReport.Step s) throws InterruptedException {
        if (stopped) return false;
        while (true) {
            ChannelStore.Channel ch = svc.current();
            if (ch != null && !ch.isDigital() && !ch.isM17()) return true;
            int a = ui.choose(s.title, "This step needs an FM (analog) channel: choose one on the Intercom page (a simplex frequency you may use, low power), then come back and tap Ready.", "Ready", "Skip this step", "Stop the session");
            if (a == 1) { s.result = TxReport.SKIPPED; s.auto = "no FM channel selected"; return false; }
            if (a != 0) { stopped = true; return false; }
        }
    }

    /** The step's instruction with the current channel; false when skipped or stopped. */
    private boolean ask(TxReport.Step s, String go, String text) throws InterruptedException {
        TxReport.Channel ch = channel(svc.current());
        String where = ch == null ? "" : String.format(Locale.US, "\n\nChannel %d · %s · TX %.5f MHz · %s power", ch.number, ch.mode, ch.txHz / 1e6, ch.power);
        int a = ui.choose(s.title, text + where, go, "Skip this step", "Stop the session");
        if (a == 1) { s.result = TxReport.SKIPPED; return false; }
        if (a != 0) { stopped = true; return false; }
        return true;
    }

    private void heard(TxReport.Step s, String question, boolean callerId) throws InterruptedException {
        int a = ui.choose(s.title, question, "Yes", "No", "Not checked");
        s.heard = a == 0 ? "yes" : a == 1 ? "no" : "not checked";
        if (callerId) { int b = ui.choose(s.title, "Did it show your caller ID (your DMR ID or name)?", "Yes", "No", "Not checked"); s.callerId = b == 0 ? "yes" : b == 1 ? "no" : "not checked"; }
        String[] n = ui.form(s.title, "Notes about what the receiving radio heard (optional).", new String[] { "Notes" }, new String[] { "" }, new boolean[] { false });
        if (n != null) s.notes = n[0].trim();
    }

    private void finish() { report.finished = TxReport.utc(System.currentTimeMillis()); }

    // ---- saving -------------------------------------------------------------------------------------------------------

    /** The callsign and DMR ID set in the app, for {@link TxReport#redact}. */
    static String callsign(Context c) { return c.getSharedPreferences("packet", Context.MODE_PRIVATE).getString("callsign", ""); }

    /**
     * Write tx-report.json into the current device report folder (a new report when there is none from today), add the
     * readable section to report.txt; 00_SUMMARY.txt picks it up when the report is shared. Returns the file.
     */
    public static File save(Context c, RadioService svc, TxReport r) throws Exception {
        File dir = DeviceReport.currentOrNew(c);
        if (dir == null) throw new Exception("no device report folder");
        String call = callsign(c); int id = svc.localId();
        String json = TxReport.redact(r.toJson().toString(2), call, id);
        File f = new File(dir, TxReport.FILE);
        try (OutputStream o = new FileOutputStream(f)) { o.write(json.getBytes("UTF-8")); }
        DeviceReport.append("Transmit test session", TxReport.redact(r.summaryText(), call, id));
        return f;
    }

    /** A copy of tx-report.json in the share folder, to send on its own. */
    public static File shareCopy(Context c, File f) throws Exception {
        File d = new File(c.getCacheDir(), "share"); d.mkdirs();
        File o = new File(d, TxReport.FILE); java.nio.file.Files.copy(f.toPath(), o.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return o;
    }
}
