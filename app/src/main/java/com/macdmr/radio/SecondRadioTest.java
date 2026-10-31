/*
 * SignalDeck - Copyright (C) 2026 IIMacGyverII
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Free software under the GNU General Public License v3 or later, with the additional terms in COPYRIGHT
 * (keep this notice and the author credit, mark modified versions, no rights to the SignalDeck name or icon).
 */
package com.macdmr.radio;

import java.util.Locale;

/**
 * The guided second-radio test for a new or untested phone: the tester keys any other FM radio (a cheap handheld is
 * enough) when SignalDeck asks, and SignalDeck measures everything itself, so nothing depends on how the instructions
 * were understood. Receive only on this phone: nothing is ever keyed here.
 * <ol>
 * <li>Reception: how soon after the signal appears the radio reports the reception (this sets the scan time per
 * channel), the audio level, and that the squelch closes after the other radio stops.</li>
 * <li>MON: the squelch held open with nobody on the air — receiver noise must arrive; a refusal by the radio is noted.</li>
 * <li>CTCSS: this phone's channel is given a 100.0 Hz receive tone (on the VFO, the channel itself is not changed):
 * the other radio transmits without a tone (must stay silent here), then with 100.0 Hz (must be heard).</li>
 * </ol>
 * The result is one section of the device report with a verdict per part and the profile values it suggests.
 * i18n: stays English (developer report/bench text, plan §1)
 */
public final class SecondRadioTest {
    private SecondRadioTest() { }

    /** Steps the screen shows; the tester taps when ready (or the step times out). */
    public interface Ui {
        /** Show the instruction and wait until the tester is ready (true) or cancels (false). */
        boolean ask(String title, String text) throws InterruptedException;
        void status(String text);
    }

    static final class Rx { boolean seen, heard; long signalAt = -1, reportAt = -1, endAt = -1; int peakDbm = -999; long audio; boolean closed; int level = -1; boolean audible() { return level > AUDIBLE; } }
    /** The loudest half second above this (RMS) is sound; a squelch that mutes gives near-silence. */
    static final int AUDIBLE = 300;

    /** Record what arrives (before any squelch decision of this app) and return the loudest half second's RMS. */
    static final class Level {
        final java.io.File file; final ClipRecorder clip; final RadioService svc;
        Level(RadioService svc, int maxMs) throws java.io.IOException { this.svc = svc; file = new java.io.File(svc.getCacheDir(), "second_radio_level.wav"); clip = new ClipRecorder(file, maxMs); svc.recordClip(clip, false); }
        int stop() {
            svc.recordClip(null, false); clip.finish();
            try {
                byte[] b = java.nio.file.Files.readAllBytes(file.toPath()); int best = 0, win = 4000;          // 0.5 s at 8 kHz
                for (int st = 44; st + 2 * win <= b.length; st += win) {
                    double q = 0; for (int i = 0; i < win; i++) { int v = (short) ((b[st + 2 * i] & 0xFF) | (b[st + 2 * i + 1] << 8)); q += (double) v * v; }
                    best = Math.max(best, (int) Math.sqrt(q / win));
                }
                return best;
            } catch (Throwable t) { return -1; } finally { file.delete(); }
        }
    }

    /** Watch for up to {@code maxMs}: the first reading clearly above the floor, the module's reception report, its end. */
    static Rx watch(RadioService svc, Ui ui, int floor, long maxMs, String what) throws InterruptedException {
        Rx r = new Rx(); long t0 = System.currentTimeMillis(), a0 = svc.rxAudioBytes(); long quietSince = -1;
        Level lv = null; try { lv = new Level(svc, (int) maxMs + 2000); } catch (Throwable ignored) { }
        while (System.currentTimeMillis() - t0 < maxMs) {
            svc.pollRssiMeasure(); Thread.sleep(200);
            long now = System.currentTimeMillis(); int dbm = svc.rssiNow(); boolean rx = svc.receivingNow();
            if (dbm > -900 && dbm >= floor + 10) { if (r.signalAt < 0) r.signalAt = now; r.seen = true; quietSince = -1; if (dbm > r.peakDbm) r.peakDbm = dbm; }
            if (rx && r.reportAt < 0) r.reportAt = now;
            if (rx) r.heard = true;
            if (r.seen && dbm > -900 && dbm < floor + 6) { if (quietSince < 0) quietSince = now; }
            if (r.seen && quietSince > 0 && now - quietSince > 2500) { r.endAt = quietSince; break; }      // the other radio stopped
            ui.status(what + String.format(Locale.US, " · signal %s · %s", dbm > -900 ? dbm + " dBm" : "—", rx ? "receiving" : "quiet"));
        }
        r.audio = svc.rxAudioBytes() - a0;
        if (lv != null) r.level = lv.stop();
        if (r.endAt > 0) {                                                     // after the other radio stopped: the module's report, and silence
            Level after = null; try { after = new Level(svc, 2500); } catch (Throwable ignored) { }
            Thread.sleep(2000);
            int l2 = after == null ? -1 : after.stop();
            r.closed = !svc.receivingNow() && (l2 < 0 || l2 <= AUDIBLE);
        }
        return r;
    }

    /** Run the test (worker thread). Returns the report section. */
    public static String run(RadioService svc, Ui ui) throws InterruptedException {
        StringBuilder rep = new StringBuilder();
        ChannelStore.Channel ch = svc.vfoActive() ? svc.vfoChannel() : svc.current();
        if (!svc.isReady() || ch == null) return "not run: SignalDeck does not have the radio\n";
        if (ch.isDigital()) return "not run: choose an analog (FM) channel first\n";
        String freq = String.format(Locale.US, "%.4f MHz", ch.rxFreq / 1e6);
        rep.append("channel: ").append(ch.name).append(" · ").append(freq).append(" FM\nprofile: ").append(svc.profile().name).append(" (").append(svc.profile().id).append(" r").append(svc.profile().revision).append(")\n\n");
        // the noise floor
        ui.status("Measuring the noise floor…");
        int floor = -999, n = 0, sum = 0;
        for (int i = 0; i < 6; i++) { svc.pollRssiMeasure(); Thread.sleep(400); int d = svc.rssiNow(); if (d > -900) { sum += d; n++; } }
        floor = n > 0 ? sum / n : -110;
        rep.append("noise floor: ").append(floor).append(" dBm\n");

        // 1. reception
        if (!ui.ask("1 of 3 · Reception", "Set your other radio to " + freq + " FM with no tone, low power, a few metres away.\n\nTap Ready, then key it and talk for about 5 seconds, then stop.")) return rep.append("cancelled\n").toString();
        Rx r = watch(svc, ui, floor, 25_000, "Key your other radio and talk for 5 s");
        long latency = r.signalAt > 0 && r.reportAt > 0 ? Math.max(0, r.reportAt - r.signalAt) : -1;
        rep.append("\n1. reception\n");
        if (!r.seen) rep.append("  verdict: no signal arrived (check the frequency and that the other radio transmits)\n");
        else {
            rep.append(String.format(Locale.US, "  signal: up to %d dBm\n  receive report: %s\n  audio: loudest half second RMS %d (%s), %d bytes\n  squelch after the other radio stopped: %s\n",
                    r.peakDbm, r.heard ? (latency >= 0 ? latency + " ms after the signal appeared" : "yes") : "NEVER reported",
                    r.level, r.audible() ? "sound" : "near silence", r.audio, r.endAt < 0 ? "not measured (the signal did not end in time)" : r.closed ? "closed (as it should)" : "STAYED OPEN"));
            rep.append("  verdict: ").append(!r.heard && !r.audible() ? "the radio saw the signal but never reported a reception or played it" : !r.audible() ? "reception reported but no sound arrived" : r.endAt > 0 && !r.closed ? "received, but the squelch stays open after the signal" : "works").append('\n');
        }

        // 2. MON
        if (!ui.ask("2 of 3 · Squelch held open (MON)", "Keep your other radio quiet.\n\nTap Ready: SignalDeck holds the squelch open for 6 seconds; the speaker should hiss.")) return rep.append("cancelled\n").toString();
        long asked = System.currentTimeMillis(), a0 = svc.rxAudioBytes(); boolean wasMon = svc.monitorOn();
        Level ml = null; try { ml = new Level(svc, 8000); } catch (Throwable ignored) { }
        if (!wasMon) svc.setMonitor(true);
        for (int i = 0; i < 12; i++) { Thread.sleep(500); ui.status("MON on \u00B7 " + (svc.rxAudioBytes() - a0) + " bytes of audio"); }
        long monAudio = svc.rxAudioBytes() - a0; boolean refused = svc.monRefusedAt() >= asked; int monLevel = ml == null ? -1 : ml.stop();
        if (!wasMon) svc.setMonitor(false);
        Thread.sleep(1500); Level al = null; try { al = new Level(svc, 2500); } catch (Throwable ignored) { } Thread.sleep(2000);
        int afterLevel = al == null ? -1 : al.stop(); boolean monClosed = !svc.receivingNow() && afterLevel <= AUDIBLE;
        rep.append("\n2. MON (squelch held open)\n  audio: loudest half second RMS ").append(monLevel).append(monLevel > AUDIBLE ? " (receiver noise)" : " (near silence)").append(", ").append(monAudio).append(" bytes").append(refused ? "\n  the radio REFUSED MON (also after retries)" : "")
           .append("\n  after MON off: ").append(monClosed ? "squelch closed (RMS " + afterLevel + ")" : "squelch STAYED OPEN (RMS " + afterLevel + ")").append('\n');
        rep.append("  verdict: ").append(refused ? "the radio refused MON" : monLevel <= AUDIBLE ? "MON on, but no sound arrived" : monClosed ? "works" : "works, but the squelch does not close afterwards").append('\n');

        // 3. CTCSS
        if (!ui.ask("3 of 3 · Tone squelch (CTCSS)", "SignalDeck now listens with a 100.0 Hz tone (only for this test; your channel is not changed).\n\nTap Ready, then key your other radio WITHOUT a tone for 5 seconds. This phone should stay silent.")) return rep.append("cancelled\n").toString();
        ChannelStore.Channel t = svc.vfoCopy(ch); t.rxType = 1; t.rxSubCode = ChannelPresets.ctcssIndex(100.0); t.name = "Tone test";
        boolean hadVfo = svc.vfoActive(); ChannelStore.Channel before = hadVfo ? svc.vfoCopy(svc.vfoChannel()) : null;
        svc.applyVfo(t, false); Thread.sleep(RadioService.RETUNE_SETTLE_MS);
        Rx noTone = watch(svc, ui, floor, 20_000, "Key WITHOUT a tone");
        boolean ok2 = ui.ask("3 of 3 · Tone squelch (CTCSS)", "Now set your other radio's transmit tone (CTCSS / CTC / T) to 100.0 Hz.\n\nTap Ready, then key it for 5 seconds. This phone should open and play it.");
        Rx withTone = ok2 ? watch(svc, ui, floor, 20_000, "Key WITH 100.0 Hz") : null;
        if (hadVfo && before != null) svc.applyVfo(before, false); else svc.exitVfo();
        rep.append("\n3. CTCSS 100.0 Hz on this phone's receive\n  without a tone: ").append(!noTone.seen ? "no signal seen (not tested)" : noTone.audible() ? "OPENED (sound came through, RMS " + noTone.level + ")" : "stayed silent (as it should; RMS " + noTone.level + ")").append(noTone.heard ? ", the module reported a reception" : "").append('\n');
        if (withTone != null) rep.append("  with 100.0 Hz: ").append(!withTone.seen ? "no signal seen (not tested)" : withTone.audible() ? "opened (as it should; RMS " + withTone.level + ")" : "stayed SILENT (the tone was not recognised; RMS " + withTone.level + ")").append('\n');
        boolean ctcssOk = noTone.seen && !noTone.audible() && withTone != null && withTone.seen && withTone.audible();
        rep.append("  verdict: ").append(ctcssOk ? "works" : !noTone.seen || withTone == null || !withTone.seen ? "incomplete" : "does not work as expected").append('\n');

        // suggestions for the profile
        rep.append("\nsuggested profile values\n");
        if (latency >= 0) { long dwell = Math.max(1500, Math.min(4000, ((latency + 1000 + 249) / 250) * 250)); rep.append("  scanDwellMs: ").append(dwell).append(" (receive report ").append(latency).append(" ms after the signal + 1 s; now ").append(svc.profile().scanDwellMs).append(")\n"); }
        else rep.append("  scanDwellMs: not measured\n");
        return rep.toString();
    }
}
